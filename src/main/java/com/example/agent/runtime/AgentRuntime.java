package com.example.agent.runtime;

import com.example.agent.client.ModelClient;
import com.example.agent.client.ModelClientException;
import com.example.agent.model.Message;
import com.example.agent.model.ModelResponse;
import com.example.agent.model.ToolCall;
import com.example.agent.tool.ToolExecutor;
import com.example.agent.tool.ToolRegistry;
import com.example.agent.workflow.FailureCategory;
import com.example.agent.workflow.RunRecord;
import com.example.agent.workflow.RunStatus;
import com.example.agent.workflow.RunStep;
import com.example.agent.workflow.RunStepStatus;
import com.example.agent.workflow.RunStore;
import com.example.agent.workflow.RuntimeConfig;
import com.example.agent.workflow.StepKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单 Agent Workflow 的显式控制循环，同时负责状态记录、预算执行和安全恢复。
 * 模型调用可以在恢复时重试（可能重复产生模型费用）；工具调用只有在 PENDING 状态才会执行，
 * 已持久化为 IN_PROGRESS 的调用恢复为 INDETERMINATE，避免自动重放可能产生副作用的操作。
 */
public final class AgentRuntime {
    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);
    private static final long MODEL_WAIT_SLICE_MILLIS = 100;

    private final ModelClient modelClient;
    private final ToolRegistry registry;
    private final ToolExecutor toolExecutor;
    private final RunStore runStore;
    private final RuntimeConfig config;
    private final ConcurrentHashMap<String, AtomicBoolean> cancellationRequests = new ConcurrentHashMap<>();

    /** 保留基础版构造方式，使用内存存储和默认阶段二预算。 */
    public AgentRuntime(ModelClient modelClient, ToolRegistry registry, ToolExecutor toolExecutor, int maxModelCalls) {
        this(modelClient, registry, toolExecutor, new com.example.agent.workflow.InMemoryRunStore(),
                RuntimeConfig.defaults(maxModelCalls));
    }

    public AgentRuntime(ModelClient modelClient, ToolRegistry registry, ToolExecutor toolExecutor,
                        RunStore runStore, RuntimeConfig config) {
        this.modelClient = java.util.Objects.requireNonNull(modelClient);
        this.registry = java.util.Objects.requireNonNull(registry);
        this.toolExecutor = java.util.Objects.requireNonNull(toolExecutor);
        this.runStore = java.util.Objects.requireNonNull(runStore);
        this.config = java.util.Objects.requireNonNull(config);
    }

    /**
     * 兼容原有 CLI/调用方：成功返回最终文本，失败抛出带 runId 和失败类别的异常。
     * 需要检查或恢复状态时使用 runTracked() 和 resume()。
     */
    public String run(String userInput) {
        RunRecord run = runTracked(userInput);
        if (run.status() == RunStatus.SUCCEEDED) return run.finalAnswer();
        if (run.failureCategory() == FailureCategory.MAX_MODEL_CALLS) {
            throw new AgentMaxIterationsException("Agent stopped after " + config.maxModelCalls() + " model calls without a final answer");
        }
        throw new AgentRunException(run.runId(), run.failureCategory());
    }

    /** 创建稳定 runId 并同步执行一项任务；任何安全边界都会写入 RunStore。 */
    public RunRecord runTracked(String userInput) {
        String runId = UUID.randomUUID().toString();
        RunRecord run = RunRecord.create(runId, userInput, config.taskTimeout());
        run.addMessage(Message.user(userInput));
        runStore.save(run); // CREATED 状态先落盘，确保任务一开始便可追踪。
        cancellationRequests.put(runId, new AtomicBoolean(false));
        try {
            return execute(run, false);
        } finally {
            cancellationRequests.remove(runId);
        }
    }

    /**
     * 恢复一个未结束的运行。只允许 CREATED/RUNNING 状态；终态任务不能重复启动。
     * 已完成的工具步骤保留，未开始工具可继续，执行中工具不会自动重放。
     */
    public RunRecord resume(String runId) {
        RunRecord run = runStore.find(runId).orElseThrow(() -> new IllegalArgumentException("Run not found"));
        if (run.status() != RunStatus.RUNNING && run.status() != RunStatus.CREATED) {
            throw new IllegalStateException("Only a non-terminal run can be resumed");
        }
        run.setResumed(true);
        run.touch(Instant.now());
        runStore.save(run);
        cancellationRequests.put(runId, new AtomicBoolean(false));
        try {
            return execute(run, true);
        } finally {
            cancellationRequests.remove(runId);
        }
    }

    /** 请求取消当前运行；正在进行的工具不能因此被视为已撤销，Runtime 会等待其返回或超时。 */
    public void cancelRun(String runId) {
        RunRecord run = runStore.find(runId).orElseThrow(() -> new IllegalArgumentException("Run not found"));
        if (run.status() != RunStatus.CREATED && run.status() != RunStatus.RUNNING) {
            throw new IllegalStateException("Only a non-terminal run can be cancelled");
        }
        cancellationRequests.computeIfAbsent(runId, ignored -> new AtomicBoolean()).set(true);
        run.transitionTo(RunStatus.CANCELLED, Instant.now(), FailureCategory.CANCELLED);
        runStore.save(run);
        log.info("runId={} status={} failureCategory={}", runId, RunStatus.CANCELLED, FailureCategory.CANCELLED);
    }

    private RunRecord execute(RunRecord run, boolean recovering) {
        try {
            if (run.status() == RunStatus.CREATED) run.transitionTo(RunStatus.RUNNING, Instant.now(), null);
            else run.touch(Instant.now());
            persist(run);
            log.info("runId={} status={} resumed={}", run.runId(), run.status(), recovering);

            if (recovering) recoverInterruptedSteps(run);
            while (true) {
                checkCancelled(run);
                checkDeadline(run);

                if (hasPendingTools(run) && run.modelCalls() >= config.maxModelCalls()) {
                    // 没有预算向模型汇报工具结果时，不产生新的工具副作用。
                    throw new WorkflowAbort(FailureCategory.MAX_MODEL_CALLS);
                }
                if (hasPendingTools(run)) executePendingTools(run);
                checkCancelled(run);
                checkDeadline(run);

                if (run.modelCalls() >= config.maxModelCalls()) {
                    throw new WorkflowAbort(FailureCategory.MAX_MODEL_CALLS);
                }
                ModelResponse response = requestModelWithRetry(run);

                if (response.hasToolCalls()) {
                    run.addMessage(Message.assistantToolCalls(response.toolCalls()));
                    if (run.modelCalls() >= config.maxModelCalls()) {
                        Instant now = Instant.now();
                        for (ToolCall call : response.toolCalls()) {
                            run.addStep(RunStep.toolPending(UUID.randomUUID().toString(), call.id(), call.name(), now)
                                    .finish(RunStepStatus.FAILED, now, FailureCategory.MAX_MODEL_CALLS, null, null));
                        }
                        persist(run);
                        throw new WorkflowAbort(FailureCategory.MAX_MODEL_CALLS);
                    }
                    if (run.toolCalls() + response.toolCalls().size() > config.maxToolCalls()) {
                        for (ToolCall call : response.toolCalls()) {
                            RunStep denied = RunStep.toolPending(UUID.randomUUID().toString(), call.id(), call.name(), Instant.now())
                                    .finish(RunStepStatus.FAILED, Instant.now(), FailureCategory.TOOL_BUDGET_EXCEEDED, null, null);
                            run.addStep(denied);
                        }
                        persist(run);
                        throw new WorkflowAbort(FailureCategory.TOOL_BUDGET_EXCEEDED);
                    }
                    Instant now = Instant.now();
                    for (ToolCall call : response.toolCalls()) {
                        run.addStep(RunStep.toolPending(UUID.randomUUID().toString(), call.id(), call.name(), now));
                    }
                    // 这是工具副作用前的安全检查点：模型调用请求和所有参数已持久化，工具仍为 PENDING。
                    persist(run);
                    executePendingTools(run);
                    continue;
                }

                run.addMessage(Message.assistant(response.content()));
                run.setFinalAnswer(response.content());
                run.transitionTo(RunStatus.SUCCEEDED, Instant.now(), null);
                persist(run);
                log.info("runId={} status={} modelCalls={} toolCalls={} durationMs={} resumed={} tokensKnown={}",
                        run.runId(), run.status(), run.modelCalls(), run.toolCalls(), run.totalDurationMillis(),
                        run.resumed(), run.tokenUsageKnown());
                return run;
            }
        } catch (WorkflowAbort abort) {
            if (abort.category == FailureCategory.CANCELLED) {
                run.transitionTo(RunStatus.CANCELLED, Instant.now(), abort.category);
            } else {
                run.transitionTo(RunStatus.FAILED, Instant.now(), abort.category);
            }
            persist(run);
            log.warn("runId={} status={} failureCategory={} modelCalls={} toolCalls={} durationMs={} resumed={}",
                    run.runId(), run.status(), abort.category, run.modelCalls(), run.toolCalls(),
                    run.totalDurationMillis(), run.resumed());
            return run;
        } catch (RuntimeException unexpected) {
            // 不记录或传播原始异常正文，避免供应商异常、参数和凭据进入运行日志。
            run.transitionTo(RunStatus.FAILED, Instant.now(), FailureCategory.UNKNOWN);
            persist(run);
            log.error("runId={} status={} failureCategory={} exceptionType={}", run.runId(), run.status(),
                    FailureCategory.UNKNOWN, unexpected.getClass().getSimpleName());
            return run;
        }
    }

    private ModelResponse requestModelWithRetry(RunRecord run) throws WorkflowAbort {
        int retries = 0;
        while (true) {
            checkCancelled(run);
            checkDeadline(run);
            if (run.modelCalls() >= config.maxModelCalls()) throw new WorkflowAbort(FailureCategory.MAX_MODEL_CALLS);

            Instant startedAt = Instant.now();
            String stepId = UUID.randomUUID().toString();
            run.addStep(RunStep.modelStarted(stepId, startedAt));
            run.incrementModelCalls();
            persist(run);
            try {
                ModelResponse response = callModelUntilDeadline(run);
                checkCancelled(run);
                Instant completedAt = Instant.now();
                run.replaceStep(stepId, run.step(stepId).finish(RunStepStatus.SUCCEEDED, completedAt,
                        null, response.promptTokens(), response.completionTokens()));
                run.addTokenUsage(response.promptTokens(), response.completionTokens());
                persist(run);
                log.info("runId={} stepId={} kind=MODEL status=SUCCEEDED durationMs={} modelCall={} tokensKnown={}",
                        run.runId(), stepId, Duration.between(startedAt, completedAt).toMillis(),
                        run.modelCalls(), response.promptTokens() != null && response.completionTokens() != null);
                return response;
            } catch (ModelClientException failure) {
                Instant completedAt = Instant.now();
                run.replaceStep(stepId, run.step(stepId).finish(RunStepStatus.FAILED, completedAt,
                        failure.category(), null, null));
                run.markTokenUsageUnknown();
                persist(run);
                log.warn("runId={} stepId={} kind=MODEL status=FAILED failureCategory={} retryable={} durationMs={}",
                        run.runId(), stepId, failure.category(), failure.retryable(),
                        Duration.between(startedAt, completedAt).toMillis());
                if (!failure.retryable() || retries >= config.maxModelRetries()
                        || run.modelCalls() >= config.maxModelCalls() || Instant.now().isAfter(run.deadlineAt())) {
                    throw new WorkflowAbort(Instant.now().isAfter(run.deadlineAt())
                            ? FailureCategory.TASK_DEADLINE : failure.category());
                }
                retries++;
                waitBeforeRetry(run, retries);
            } catch (WorkflowAbort abort) {
                Instant completedAt = Instant.now();
                run.replaceStep(stepId, run.step(stepId).finish(RunStepStatus.FAILED, completedAt,
                        abort.category, null, null));
                run.markTokenUsageUnknown();
                persist(run);
                throw abort;
            }
        }
    }

    private ModelResponse callModelUntilDeadline(RunRecord run) throws ModelClientException, WorkflowAbort {
        FutureTask<ModelResponse> task = new FutureTask<>(
                () -> modelClient.complete(run.messages(), registry.definitions()));
        Thread.ofVirtual().name("agent-model-" + run.runId()).start(task);
        while (true) {
            checkCancelled(run);
            long remaining = Duration.between(Instant.now(), run.deadlineAt()).toMillis();
            if (remaining <= 0) {
                task.cancel(true);
                throw new ModelClientException(FailureCategory.MODEL_TIMEOUT, true);
            }
            try {
                return task.get(Math.min(MODEL_WAIT_SLICE_MILLIS, remaining), TimeUnit.MILLISECONDS);
            } catch (TimeoutException waiting) {
                // 以短间隔轮询，以便取消请求可以尽快中断模型等待。
            } catch (InterruptedException interrupted) {
                task.cancel(true);
                Thread.currentThread().interrupt();
                throw new WorkflowAbort(FailureCategory.CANCELLED);
            } catch (CancellationException cancelled) {
                throw new WorkflowAbort(FailureCategory.CANCELLED);
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof ModelClientException modelFailure) throw modelFailure;
                throw new ModelClientException(FailureCategory.MODEL_UNKNOWN, false);
            }
        }
    }

    private void waitBeforeRetry(RunRecord run, int retryNumber) throws WorkflowAbort {
        long pauseMillis = Math.min(500, 50L << Math.min(retryNumber - 1, 3));
        long remaining = Duration.between(Instant.now(), run.deadlineAt()).toMillis();
        if (remaining <= 0) throw new WorkflowAbort(FailureCategory.TASK_DEADLINE);
        try {
            Thread.sleep(Math.min(pauseMillis, remaining));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new WorkflowAbort(FailureCategory.CANCELLED);
        }
        checkCancelled(run);
        checkDeadline(run);
    }

    private void executePendingTools(RunRecord run) throws WorkflowAbort {
        for (RunStep step : run.steps()) {
            if (step.kind() != StepKind.TOOL || step.status() != RunStepStatus.PENDING) continue;
            checkCancelled(run);
            checkDeadline(run);
            ToolCall call = findToolCall(run.messages(), step.toolCallId());
            if (call == null) throw new WorkflowAbort(FailureCategory.UNKNOWN);
            if (run.toolCalls() >= config.maxToolCalls()) throw new WorkflowAbort(FailureCategory.TOOL_BUDGET_EXCEEDED);

            Instant startedAt = Instant.now();
            run.replaceStep(step.stepId(), step.start(startedAt));
            run.incrementToolCalls();
            persist(run); // IN_PROGRESS 必须在调用 Java 工具之前持久化。
            Duration remaining = Duration.between(Instant.now(), run.deadlineAt());
            var result = toolExecutor.execute(call, run.runId(), step.stepId(), remaining);
            Instant completedAt = Instant.now();
            RunStepStatus status = result.outcomeUnknown() ? RunStepStatus.INDETERMINATE
                    : result.success() ? RunStepStatus.SUCCEEDED : RunStepStatus.FAILED;
            run.replaceStep(step.stepId(), run.step(step.stepId()).finish(status, completedAt,
                    result.failureCategory(), null, null));
            run.addMessage(Message.toolResult(result.toolCallId(), result.toolName(), result.asMessageContent()));
            persist(run);
            log.info("runId={} stepId={} kind=TOOL tool={} status={} failureCategory={} durationMs={} toolCall={}",
                    run.runId(), step.stepId(), step.toolName(), status, result.failureCategory(),
                    Duration.between(startedAt, completedAt).toMillis(), run.toolCalls());
            if (Instant.now().isAfter(run.deadlineAt())) throw new WorkflowAbort(FailureCategory.TASK_DEADLINE);
            checkCancelled(run);
        }
    }

    private void recoverInterruptedSteps(RunRecord run) {
        for (RunStep step : run.steps()) {
            if (step.status() != RunStepStatus.IN_PROGRESS) continue;
            Instant now = Instant.now();
            if (step.kind() == StepKind.MODEL) {
                run.replaceStep(step.stepId(), step.finish(RunStepStatus.FAILED, now,
                        FailureCategory.MODEL_UNKNOWN, null, null));
                run.markTokenUsageUnknown();
                log.warn("runId={} stepId={} kind=MODEL recovery=retry failureCategory={}",
                        run.runId(), step.stepId(), FailureCategory.MODEL_UNKNOWN);
            } else {
                run.replaceStep(step.stepId(), step.finish(RunStepStatus.INDETERMINATE, now,
                        FailureCategory.TOOL_TIMEOUT_UNKNOWN, null, null));
                if (!hasToolResult(run.messages(), step.toolCallId())) {
                    run.addMessage(Message.toolResult(step.toolCallId(), step.toolName(),
                            "{\"error\":\"Previous execution outcome is unknown; tool was not replayed\"}"));
                }
                log.warn("runId={} stepId={} kind=TOOL tool={} recovery=not_replayed failureCategory={}",
                        run.runId(), step.stepId(), step.toolName(), FailureCategory.TOOL_TIMEOUT_UNKNOWN);
            }
            persist(run);
        }
    }

    private ToolCall findToolCall(List<Message> messages, String callId) {
        return messages.stream().filter(message -> message.role() == Message.Role.ASSISTANT)
                .flatMap(message -> message.toolCalls().stream())
                .filter(call -> call.id().equals(callId)).findFirst().orElse(null);
    }

    private boolean hasToolResult(List<Message> messages, String callId) {
        return messages.stream().anyMatch(message -> message.role() == Message.Role.TOOL
                && callId.equals(message.toolCallId()));
    }

    private boolean hasPendingTools(RunRecord run) {
        return run.steps().stream().anyMatch(step -> step.kind() == StepKind.TOOL
                && step.status() == RunStepStatus.PENDING);
    }

    private void checkDeadline(RunRecord run) throws WorkflowAbort {
        if (!Instant.now().isBefore(run.deadlineAt())) throw new WorkflowAbort(FailureCategory.TASK_DEADLINE);
    }

    private void checkCancelled(RunRecord run) throws WorkflowAbort {
        AtomicBoolean requested = cancellationRequests.get(run.runId());
        if (requested != null && requested.get()) throw new WorkflowAbort(FailureCategory.CANCELLED);
    }

    private void persist(RunRecord run) {
        run.touch(Instant.now());
        runStore.save(run);
    }

    private static final class WorkflowAbort extends Exception {
        private final FailureCategory category;
        private WorkflowAbort(FailureCategory category) { this.category = category; }
    }
}
