package com.example.agent.collaboration;

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
import com.example.agent.workflow.RunStoreUnavailableException;
import com.example.agent.workflow.LeaseToken;
import com.example.agent.workflow.LeaseLostException;
import com.example.agent.workflow.TaskLeaseToken;
import com.example.agent.workflow.RunType;
import com.example.agent.workflow.RunLeaseHeartbeat;
import com.example.agent.workflow.RunLeasePolicy;
import com.example.agent.workflow.StepKind;
import com.example.agent.observability.AgentTelemetry;
import io.micrometer.observation.Observation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 最小 Supervisor–Worker 执行器。父 Run 统一持有计划、子任务、对话检查点和全局预算；
 * 子任务不是独立 Run。Planner 和总结器只使用一次结构化规划及一次汇总调用。
 */
public final class CollaborativeWorkflow implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CollaborativeWorkflow.class);
    private static final String SUPERVISOR_PROMPT = """
            You are the workflow supervisor. Return exactly one JSON object and no markdown.
            Decide whether the request needs delegation. Use {"route":"NO_SPLIT","tasks":[],"deliverables":[]} when one agent can handle it.
            Otherwise use {"route":"DELEGATE","tasks":[{"taskId":"stable-id","workerId":"registered-id","description":"specific task","dependencies":[],"deliverableIds":["item-id"]}],"deliverables":[{"deliverableId":"item-id","description":"expected output"}]}.
            Select only registered workers. Dependencies must reference task IDs. Keep tasks independent unless one needs another result.
            """;
    private static final String SUMMARY_PROMPT = """
            You are the supervisor summarizing worker results for the original request.
            Use only the supplied task outcomes. Do not claim failed or skipped work succeeded.
            State any missing deliverables and incomplete tasks clearly. Be concise.
            """;

    private final ModelClient model;
    private final ToolRegistry allTools;
    private final WorkerRegistry workers;
    private final RunStore store;
    private final CollaborationConfig config;
    private final ObjectMapper json;
    private final TaskPlanValidator planValidator;
    private final AgentTelemetry telemetry;
    private final RunLeasePolicy leasePolicy;
    private final ConcurrentHashMap<String, Control> controls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LeaseToken> runLeases = new ConcurrentHashMap<>();
    private final ThreadLocal<TaskLeaseToken> activeTaskLease = new ThreadLocal<>();
    private final ThreadLocal<LeaseToken> activeParentLease = new ThreadLocal<>();

    public CollaborativeWorkflow(ModelClient model, ToolRegistry allTools, WorkerRegistry workers,
                                 RunStore store,
                                 CollaborationConfig config, ObjectMapper json) {
        this(model, allTools, workers, store, config, json, AgentTelemetry.noop());
    }

    public CollaborativeWorkflow(ModelClient model, ToolRegistry allTools, WorkerRegistry workers,
                                 RunStore store, CollaborationConfig config, ObjectMapper json,
                                 AgentTelemetry telemetry) {
        this(model, allTools, workers, store, config, json, telemetry, RunLeasePolicy.DEFAULT);
    }

    public CollaborativeWorkflow(ModelClient model, ToolRegistry allTools, WorkerRegistry workers,
                                 RunStore store, CollaborationConfig config, ObjectMapper json,
                                 AgentTelemetry telemetry, RunLeasePolicy leasePolicy) {
        this.model = java.util.Objects.requireNonNull(model);
        this.allTools = java.util.Objects.requireNonNull(allTools);
        this.workers = java.util.Objects.requireNonNull(workers);
        this.store = java.util.Objects.requireNonNull(store);
        this.config = java.util.Objects.requireNonNull(config);
        this.json = java.util.Objects.requireNonNull(json);
        this.telemetry = java.util.Objects.requireNonNull(telemetry);
        this.leasePolicy = java.util.Objects.requireNonNull(leasePolicy);
        this.planValidator = new TaskPlanValidator(workers, config.maxTasks());
        validateWorkerToolAllowLists();
    }

    public RunRecord runTracked(String input) {
        String runId = UUID.randomUUID().toString();
        RunRecord run = RunRecord.create(runId, input, config.timeout(), RunType.COLLABORATIVE);
        run.addMessage(Message.user(input));
        Control control = new Control();
        control.active = true;
        controls.put(runId, control);
        try {
            store.save(run);
            return execute(run, control, false);
        } finally {
            controls.remove(runId, control);
        }
    }

    public RunRecord resume(String runId) {
        Control control = controls.computeIfAbsent(runId, ignored -> new Control());
        try {
            RunRecord run;
            synchronized (control) {
                if (control.active) throw new IllegalStateException("Run is already active");
                run = store.find(runId).orElseThrow(() -> new IllegalArgumentException("Run not found"));
                if (run.runType() != RunType.COLLABORATIVE)
                    throw new IllegalStateException("Run belongs to a different workflow type");
                if (run.status() != RunStatus.CREATED && run.status() != RunStatus.RUNNING)
                    throw new IllegalStateException("Only a non-terminal run can be resumed");
                control.active = true;
                run.setResumed(true);
                run.touch(Instant.now());
            }
            return execute(run, control, true);
        } finally {
            synchronized (control) { control.active = false; }
            controls.remove(runId, control);
        }
    }

    /** 活跃 Run 采用请求取消、执行线程确认终态；非活跃检查点可直接终止。 */
    public void cancelRun(String runId) {
        Control control = controls.computeIfAbsent(runId, ignored -> new Control());
        try {
            synchronized (control) {
                RunRecord run = store.find(runId).orElseThrow(() -> new IllegalArgumentException("Run not found"));
                if (run.status() != RunStatus.CREATED && run.status() != RunStatus.RUNNING)
                    throw new IllegalStateException("Only a non-terminal run can be cancelled");
                if (!store.requestCancellation(runId, Instant.now()))
                    throw new IllegalStateException("Run is already terminal");
                if (control.active) {
                    control.cancelRequested = true;
                    control.cancelActiveWork();
                } else {
                    Instant now = Instant.now();
                    store.claim(runId, UUID.randomUUID().toString(), now, Duration.ofSeconds(30)).ifPresent(lease -> {
                        RunRecord latest = store.find(runId).orElseThrow();
                        latest.transitionTo(RunStatus.CANCELLED, now, FailureCategory.CANCELLED);
                        if (!store.saveFenced(latest, lease, now)) throw new LeaseLostException(runId);
                    });
                }
            }
        } finally {
            if (!control.active) controls.remove(runId, control);
        }
    }

    /** Requests cancellation for active workflows before the Spring application context closes. */
    @Override
    public void close() {
        for (String runId : List.copyOf(controls.keySet())) {
            try {
                cancelRun(runId);
            } catch (RuntimeException rejected) {
                log.warn("runId={} shutdownCancellation=notAccepted", runId);
            }
        }
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!controls.isEmpty() && System.nanoTime() < until) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private RunRecord execute(RunRecord run, Control control, boolean recovering) {
        LeaseToken lease = store.claim(run.runId(), UUID.randomUUID().toString(), Instant.now(),
                leasePolicy.duration())
                .orElseThrow(() -> new IllegalStateException("Run is already leased or terminal"));
        runLeases.put(run.runId(), lease);
        RunLeaseHeartbeat heartbeat = new RunLeaseHeartbeat(store, lease, leasePolicy, control::loseLease);
        run.setLeaseOwner(lease.ownerId());
        run.setFencingToken(lease.fencingToken());
        Observation runObservation = telemetry.startRun(run, "collaborative");
        SharedRunBudget budget = new SharedRunBudget(run, config, store, lease);
        try {
            if (run.status() == RunStatus.CREATED) run.transitionTo(RunStatus.RUNNING, Instant.now(), null);
            persist(run);
            log.info("runId={} workflow=SUPERVISOR status=RUNNING resumed={}", run.runId(), recovering);
            if (recovering) recoverInterruptedWork(run);

            if (run.taskPlan() == null) createAndSavePlan(run, control, budget);
            if (run.taskPlan().route() == TaskPlan.Route.NO_SPLIT) {
                executeSingleAgentFallback(run, control, budget);
                return complete(run, control, RunStatus.SUCCEEDED, null);
            }

            executeTasks(run, control, budget);
            checkCancelled(run, control);
            checkDeadline(run);
            List<ChildTaskRecord> successful = run.childTasks().stream()
                    .filter(task -> task.status() == TaskStatus.SUCCEEDED).toList();
            if (successful.isEmpty()) throw new WorkflowAbort(FailureCategory.NO_SUCCESSFUL_TASKS);

            Set<String> covered = new HashSet<>();
            successful.forEach(task -> covered.addAll(task.deliverableIds()));
            List<String> missing = run.taskPlan().deliverables().stream()
                    .map(TaskPlan.Deliverable::deliverableId).filter(id -> !covered.contains(id)).toList();
            String summary = summarize(run, missing, control, budget);
            run.setFinalAnswer(appendMissing(summary, missing));
            run.addMessage(Message.assistant(run.finalAnswer()));
            boolean partial = !missing.isEmpty() || run.childTasks().stream()
                    .anyMatch(task -> task.status() != TaskStatus.SUCCEEDED);
            return complete(run, control, partial ? RunStatus.PARTIAL : RunStatus.SUCCEEDED,
                    partial ? FailureCategory.WORKER_FAILED : null);
        } catch (LeaseLostException lost) {
            log.warn("runId={} workflow=SUPERVISOR execution=lease_lost fencingToken={}",
                    run.runId(), lease.fencingToken());
            run = store.find(run.runId()).orElse(run);
            return run;
        } catch (WorkflowAbort abort) {
            settleUnfinishedTasks(run, abort.category);
            RunStatus terminal = abort.category == FailureCategory.CANCELLED
                    ? RunStatus.CANCELLED : RunStatus.FAILED;
            return complete(run, control, terminal, abort.category);
        } catch (RuntimeException unexpected) {
            log.warn("runId={} workflow=SUPERVISOR failureClass={}", run.runId(), unexpected.getClass().getSimpleName());
            settleUnfinishedTasks(run, FailureCategory.UNKNOWN);
            return complete(run, control, RunStatus.FAILED, FailureCategory.UNKNOWN);
        } finally {
            heartbeat.close();
            ExecutorService executor = control.workerExecutor;
            if (executor != null) executor.shutdownNow();
            telemetry.finishRun(runObservation, run, "collaborative");
            runLeases.remove(run.runId(), lease);
        }
    }

    private void createAndSavePlan(RunRecord run, Control control, SharedRunBudget budget) throws WorkflowAbort {
        Instant start = Instant.now();
        String planStepId = UUID.randomUUID().toString();
        run.addStep(RunStep.workflow(planStepId, StepKind.PLAN, null, "supervisor", "PLAN", start));
        persist(run);
        ModelResponse response;
        try {
            response = invokeModel(run, List.of(Message.system(SUPERVISOR_PROMPT),
                    Message.user(supervisorRequest() + "\n\nUser request:\n" + run.userInput())),
                    List.of(), "supervisor", null, Integer.MAX_VALUE, budget, control);
        } catch (WorkflowAbort abort) {
            finishWorkflowStep(run, planStepId, RunStepStatus.FAILED, abort.category);
            throw abort;
        } catch (ModelClientException failure) {
            finishWorkflowStep(run, planStepId, RunStepStatus.FAILED, failure.category());
            throw new WorkflowAbort(failure.category());
        }
        TaskPlan plan;
        try {
            plan = json.readValue(response.content(), TaskPlan.class);
            planValidator.validate(plan);
        } catch (Exception invalid) {
            finishWorkflowStep(run, planStepId, RunStepStatus.FAILED, FailureCategory.PLAN_INVALID);
            throw new WorkflowAbort(FailureCategory.PLAN_INVALID);
        }
        run.replaceStep(planStepId, run.step(planStepId).finish(RunStepStatus.SUCCEEDED, Instant.now(), null,
                null, null));
        run.setTaskPlan(plan);
        if (plan.route() == TaskPlan.Route.DELEGATE) {
            plan.tasks().forEach(task -> run.addChildTask(ChildTaskRecord.pending(task)));
        }
        persist(run);
    }

    private String supervisorRequest() {
        String roster = workers.all().stream().map(worker -> "- " + worker.workerId() + ": "
                + worker.responsibility() + "; tools=" + worker.allowedTools())
                .reduce((a, b) -> a + "\n" + b).orElse("(no workers registered)");
        return "Registered workers (only these may be selected):\n" + roster
                + "\nMaximum tasks: " + config.maxTasks()
                + "\nFor every expected output, add a deliverable and map it to at least one task.";
    }

    private void executeSingleAgentFallback(RunRecord run, Control control, SharedRunBudget budget)
            throws WorkflowAbort {
        List<Message> messages = run.agentMessages();
        if (messages.isEmpty()) {
            messages = new ArrayList<>(List.of(Message.system("You are a helpful assistant. Use the available tools when needed."),
                    Message.user(run.userInput())));
            run.setAgentMessages(messages);
            persist(run);
        }
        ToolRegistry fullRegistry = allTools;
        try (ToolExecutor executor = new ToolExecutor(fullRegistry, config.toolTimeout())) {
            String answer = runAgentLoop(run, null, "single-agent", messages, fullRegistry, executor,
                    Integer.MAX_VALUE, Integer.MAX_VALUE, control, budget);
            run.setFinalAnswer(answer);
            run.addMessage(Message.assistant(answer));
        }
    }

    private void executeTasks(RunRecord run, Control control, SharedRunBudget budget) throws WorkflowAbort {
        if (config.separateTaskWorkers()) {
            awaitSeparateWorkers(run, control);
            return;
        }
        ExecutorService executor = Executors.newFixedThreadPool(config.maxParallelism());
        control.workerExecutor = executor;
        Map<String, PlannedTask> specs = new HashMap<>();
        run.taskPlan().tasks().forEach(task -> specs.put(task.taskId(), task));
        while (true) {
            checkCancelled(run, control);
            checkDeadline(run);
            markTasksWithFailedDependencies(run);
            List<ChildTaskRecord> unfinished = run.childTasks().stream()
                    .filter(task -> task.status() == TaskStatus.PENDING || task.status() == TaskStatus.IN_PROGRESS).toList();
            if (unfinished.isEmpty()) return;
            List<ChildTaskRecord> ready = unfinished.stream()
                    .filter(task -> task.dependencies().stream().allMatch(id -> taskStatus(run, id) == TaskStatus.SUCCEEDED))
                    .sorted(Comparator.comparing(ChildTaskRecord::taskId)).toList();
            if (ready.isEmpty()) {
                markUnresolvableTasksSkipped(run);
                continue;
            }
            for (int offset = 0; offset < ready.size(); offset += config.maxParallelism()) {
                checkCancelled(run, control);
                checkDeadline(run);
                if (budget.remainingModelCalls() == 0)
                    throw new WorkflowAbort(FailureCategory.MAX_MODEL_CALLS);
                List<ChildTaskRecord> batch = ready.subList(offset,
                        Math.min(ready.size(), offset + config.maxParallelism()));
                List<Future<?>> futures = new ArrayList<>();
                for (ChildTaskRecord task : batch) {
                    futures.add(executor.submit(() -> executeChildTask(run, task.taskId(), specs.get(task.taskId()),
                            control, budget)));
                }
                for (Future<?> future : futures) awaitWorker(run, future, control);
                refreshChildTaskProjection(run);
            }
        }
    }

    private void awaitSeparateWorkers(RunRecord run, Control control) throws WorkflowAbort {
        try {
            while (true) {
                checkCancelled(run, control);
                checkDeadline(run);
                refreshChildTaskProjection(run);
                markTasksWithFailedDependencies(run);
                boolean unfinished = run.childTasks().stream().anyMatch(task ->
                        task.status() == TaskStatus.PENDING || task.status() == TaskStatus.IN_PROGRESS);
                if (!unfinished) return;
                Thread.sleep(100);
            }
        } catch (WorkflowAbort abort) {
            if (abort.category == FailureCategory.CANCELLED || abort.category == FailureCategory.TASK_DEADLINE)
                drainSeparateWorkers(run);
            throw abort;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new WorkflowAbort(control.cancelRequested ? FailureCategory.CANCELLED : FailureCategory.UNKNOWN);
        }
    }

    private void drainSeparateWorkers(RunRecord run) {
        long graceMillis = Math.min(30_000, config.toolTimeout().toMillis() + 1_000);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMillis);
        while (System.nanoTime() < deadline) {
            refreshChildTaskProjection(run);
            boolean active = run.childTasks().stream().anyMatch(task -> task.status() == TaskStatus.IN_PROGRESS);
            if (!active) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void executeChildTask(RunRecord run, String taskId, PlannedTask plan,
                                  Control control, SharedRunBudget budget) {
        LeaseToken parentLease = runLeases.get(run.runId());
        Duration childLeaseDuration = remaining(run).plusSeconds(30);
        TaskLeaseToken taskLease = parentLease == null ? null : store.claimChildTask(parentLease, taskId,
                UUID.randomUUID().toString(), Instant.now(), childLeaseDuration).orElse(null);
        if (taskLease == null) throw new LeaseLostException(run.runId());
        activeParentLease.set(parentLease);
        activeTaskLease.set(taskLease);
        try {
            executeClaimedChildTask(run, taskId, plan, control, budget);
        } finally {
            activeTaskLease.remove();
            activeParentLease.remove();
        }
    }

    /** Executes a task claimed by a separate database-polling worker process. */
    public void executeClaimedTask(RunRecord run, LeaseToken parentLease, TaskLeaseToken taskLease) {
        if (run == null || parentLease == null || taskLease == null
                || run.runType() != RunType.COLLABORATIVE || !run.runId().equals(parentLease.runId())
                || !run.runId().equals(taskLease.runId()) || run.status() != RunStatus.RUNNING) {
            throw new IllegalArgumentException("Invalid collaborative task claim");
        }
        PlannedTask plan = run.taskPlan() == null ? null : run.taskPlan().tasks().stream()
                .filter(value -> value.taskId().equals(taskLease.taskId())).findFirst().orElse(null);
        if (plan == null) throw new IllegalStateException("Claimed task is absent from the persisted task plan");
        Control control = new Control();
        SharedRunBudget budget = new SharedRunBudget(run, config, store, parentLease);
        activeParentLease.set(parentLease);
        activeTaskLease.set(taskLease);
        try {
            executeClaimedChildTask(run, taskLease.taskId(), plan, control, budget);
        } finally {
            activeTaskLease.remove();
            activeParentLease.remove();
        }
    }

    private void executeClaimedChildTask(RunRecord run, String taskId, PlannedTask plan,
                                         Control control, SharedRunBudget budget) {
        ChildTaskRecord task = childTask(run, taskId);
        WorkerSpec worker = workers.find(plan.workerId()).orElseThrow();
        List<Message> messages = task.messages();
        if (task.status() == TaskStatus.PENDING || messages.isEmpty()) {
            messages = workerMessages(run, plan, worker);
            task = task.start(messages, Instant.now());
            run.replaceChildTask(taskId, task);
            run.addStep(RunStep.workflow(UUID.randomUUID().toString(), StepKind.WORKER,
                    taskId, worker.workerId(), "WORKER", task.startedAt()));
            persist(run);
        }
        String workerStepId = run.steps().stream().filter(step -> step.kind() == StepKind.WORKER
                        && taskId.equals(step.taskId()) && step.status() == RunStepStatus.IN_PROGRESS)
                .map(RunStep::stepId).findFirst().orElseGet(() -> {
                    RunStep step = RunStep.workflow(UUID.randomUUID().toString(), StepKind.WORKER,
                            taskId, worker.workerId(), "WORKER_RESUME", Instant.now());
                    run.addStep(step);
                    return step.stepId();
                });
        ToolRegistry restricted = allTools.restrictedTo(worker.allowedTools());
        try (ToolExecutor executor = new ToolExecutor(restricted, config.toolTimeout())) {
            String result = runAgentLoop(run, taskId, worker.workerId(), messages, restricted, executor,
                    worker.maxModelCalls(), worker.maxToolCalls(), control, budget);
            if (result == null || result.isBlank()) throw new ModelClientException(FailureCategory.WORKER_FAILED, false);
            Instant now = Instant.now();
            ChildTaskRecord current = childTask(run, taskId).finish(TaskStatus.SUCCEEDED, result, null, now);
            run.replaceChildTask(taskId, current);
            finishWorkflowStep(run, workerStepId, RunStepStatus.SUCCEEDED, null);
            persist(run);
            log.info("runId={} taskId={} workerId={} status=SUCCEEDED durationMs={}", run.runId(), taskId,
                    worker.workerId(), current.durationMillis());
        } catch (LeaseLostException lost) {
            throw lost;
        } catch (WorkflowAbort abort) {
            boolean toolOutcomeUnknown = run.steps().stream().anyMatch(step -> taskId.equals(step.taskId())
                    && step.kind() == StepKind.TOOL && step.status() == RunStepStatus.INDETERMINATE);
            TaskStatus status = toolOutcomeUnknown ? TaskStatus.INDETERMINATE : TaskStatus.FAILED;
            ChildTaskRecord current = childTask(run, taskId).finish(status, null, abort.category, Instant.now());
            run.replaceChildTask(taskId, current);
            finishWorkflowStep(run, workerStepId, status == TaskStatus.INDETERMINATE
                    ? RunStepStatus.INDETERMINATE : RunStepStatus.FAILED, abort.category);
            persist(run);
            if (abort.category == FailureCategory.MAX_MODEL_CALLS
                    || abort.category == FailureCategory.TOOL_BUDGET_EXCEEDED
                    || abort.category == FailureCategory.TASK_DEADLINE
                    || abort.category == FailureCategory.CANCELLED) {
                throw new java.util.concurrent.CompletionException(abort);
            }
        } catch (ModelClientException failure) {
            ChildTaskRecord current = childTask(run, taskId).finish(TaskStatus.FAILED, null,
                    failure.category(), Instant.now());
            run.replaceChildTask(taskId, current);
            finishWorkflowStep(run, workerStepId, RunStepStatus.FAILED, failure.category());
            persist(run);
        } catch (RuntimeException failure) {
            log.warn("runId={} taskId={} workerExecution=failure failureClass={}", run.runId(), taskId,
                    failure.getClass().getSimpleName());
            ChildTaskRecord current = childTask(run, taskId).finish(TaskStatus.FAILED, null,
                    FailureCategory.WORKER_FAILED, Instant.now());
            run.replaceChildTask(taskId, current);
            finishWorkflowStep(run, workerStepId, RunStepStatus.FAILED, FailureCategory.WORKER_FAILED);
            persist(run);
        }
    }

    private String runAgentLoop(RunRecord run, String taskId, String workerId, List<Message> initialMessages,
                                ToolRegistry visibleTools, ToolExecutor executor, int taskModelLimit,
                                int taskToolLimit, Control control, SharedRunBudget budget)
            throws WorkflowAbort, ModelClientException {
        List<Message> messages = new ArrayList<>(initialMessages);
        while (true) {
            checkCancelled(run, control);
            checkDeadline(run);
            ensureCurrentTaskLease(run);
            executePendingAfterRecovery(run, visibleTools, executor, taskId, workerId, messages, control, budget);
            ChildTaskRecord task = taskId == null ? null : childTask(run, taskId);
            if (task != null && task.modelCalls() >= taskModelLimit)
                throw new WorkflowAbort(FailureCategory.WORKER_BUDGET_EXCEEDED);
            ModelResponse response = invokeModel(run, messages, visibleTools.definitions(), workerId,
                    taskId, taskModelLimit, budget, control);
            if (response.hasToolCalls()) {
                ChildTaskRecord latestTask = taskId == null ? null : childTask(run, taskId);
                if (latestTask != null && latestTask.toolCalls() + response.toolCalls().size() > taskToolLimit) {
                    recordDeniedToolBatch(run, taskId, workerId, messages, response.toolCalls(),
                            FailureCategory.WORKER_BUDGET_EXCEEDED);
                    throw new WorkflowAbort(FailureCategory.WORKER_BUDGET_EXCEEDED);
                }
                messages.add(Message.assistantToolCalls(response.toolCalls()));
                List<RunStep> pendingSteps = response.toolCalls().stream().map(call -> RunStep.toolPending(
                        UUID.randomUUID().toString(), call.id(), call.name(), Instant.now(), taskId, workerId)).toList();
                pendingSteps.forEach(run::addStep);
                saveMessages(run, taskId, messages);
                persist(run);
                if (!budget.reserveToolCalls(pendingSteps.stream().map(RunStep::stepId).toList(),
                        activeTaskLease.get())) {
                    pendingSteps.forEach(step -> run.replaceStep(step.stepId(), run.step(step.stepId()).finish(
                            RunStepStatus.FAILED, Instant.now(), FailureCategory.TOOL_BUDGET_EXCEEDED, null, null)));
                    persist(run);
                    throw new WorkflowAbort(FailureCategory.TOOL_BUDGET_EXCEEDED);
                }
                if (latestTask != null) {
                    latestTask = latestTask.addToolCalls(response.toolCalls().size());
                    run.replaceChildTask(taskId, latestTask);
                    persist(run);
                }
                for (int i = 0; i < response.toolCalls().size(); i++) {
                    checkCancelled(run, control);
                    checkDeadline(run);
                    ToolCall call = response.toolCalls().get(i);
                    RunStep pending = pendingSteps.get(i);
                    run.replaceStep(pending.stepId(), run.step(pending.stepId()).start(Instant.now()));
                    persist(run); // 工具副作用前保存 IN_PROGRESS。
                    ToolExecutor toolExecutor = executor;
                    Observation stepObservation = telemetry.startStep(run, run.step(pending.stepId()), "collaborative");
                    com.example.agent.tool.ToolResult result;
                    try (var scope = stepObservation.openScope()) {
                        ensureCurrentTaskLease(run);
                        result = toolExecutor.execute(call, run.runId(), pending.stepId(), remaining(run));
                    } catch (RuntimeException failure) {
                        stepObservation.error(failure);
                        stepObservation.stop();
                        throw failure;
                    }
                    RunStepStatus state = result.outcomeUnknown() ? RunStepStatus.INDETERMINATE
                            : result.success() ? RunStepStatus.SUCCEEDED : RunStepStatus.FAILED;
                    run.replaceStep(pending.stepId(), run.step(pending.stepId()).finish(state, Instant.now(),
                            result.failureCategory(), null, null));
                    messages.add(Message.toolResult(result.toolCallId(), result.toolName(), result.asMessageContent()));
                    saveMessages(run, taskId, messages);
                    persist(run);
                    telemetry.finishStep(stepObservation, run.step(pending.stepId()), "collaborative");
                }
                continue;
            }
            messages.add(Message.assistant(response.content()));
            saveMessages(run, taskId, messages);
            persist(run);
            return response.content();
        }
    }

    private ModelResponse invokeModel(RunRecord run, List<Message> messages, List<com.example.agent.model.ToolDefinition> tools,
                                      String workerId, String taskId, int taskModelLimit,
                                      SharedRunBudget budget, Control control)
            throws WorkflowAbort, ModelClientException {
        int retries = 0;
        while (true) {
            checkCancelled(run, control);
            checkDeadline(run);
            ChildTaskRecord currentTask = taskId == null ? null : childTask(run, taskId);
            if (currentTask != null && currentTask.modelCalls() >= taskModelLimit)
                throw new WorkflowAbort(FailureCategory.WORKER_BUDGET_EXCEEDED);
            String stepId = UUID.randomUUID().toString();
            Instant started = Instant.now();
            RunStep modelStep = RunStep.modelStarted(stepId, started, taskId, workerId);
            run.addStep(modelStep);
            if (!budget.reserveModelCall(stepId, activeTaskLease.get())) {
                run.replaceStep(stepId, modelStep.finish(RunStepStatus.FAILED, Instant.now(),
                        FailureCategory.MAX_MODEL_CALLS, null, null));
                persist(run);
                throw new WorkflowAbort(FailureCategory.MAX_MODEL_CALLS);
            }
            if (currentTask != null) {
                run.replaceChildTask(taskId, currentTask.addModelCall());
            }
            persist(run);
            Observation stepObservation = telemetry.startStep(run, run.step(stepId), "collaborative");
            try {
                ModelResponse response;
                try (var scope = stepObservation.openScope()) {
                    response = callModel(messages, tools, run, control);
                }
                checkCancelled(run, control);
                run.replaceStep(stepId, run.step(stepId).finish(RunStepStatus.SUCCEEDED, Instant.now(), null,
                        response.promptTokens(), response.completionTokens()));
                run.addTokenUsage(response.promptTokens(), response.completionTokens());
                persist(run);
                telemetry.finishStep(stepObservation, run.step(stepId), "collaborative");
                return response;
            } catch (WorkflowAbort abort) {
                run.replaceStep(stepId, run.step(stepId).finish(RunStepStatus.FAILED, Instant.now(), abort.category, null, null));
                run.markTokenUsageUnknown();
                persist(run);
                telemetry.finishStep(stepObservation, run.step(stepId), "collaborative");
                throw abort;
            } catch (ModelClientException failure) {
                run.replaceStep(stepId, run.step(stepId).finish(RunStepStatus.FAILED, Instant.now(), failure.category(), null, null));
                run.markTokenUsageUnknown();
                persist(run);
                telemetry.finishStep(stepObservation, run.step(stepId), "collaborative");
                if (!failure.retryable() || retries >= config.maxModelRetries()
                        || budget.remainingModelCalls() == 0 || !Instant.now().isBefore(run.deadlineAt())) throw failure;
                retries++;
            }
        }
    }

    private void recordDeniedToolBatch(RunRecord run, String taskId, String workerId,
                                      List<Message> messages, List<ToolCall> calls, FailureCategory category) {
        messages.add(Message.assistantToolCalls(calls));
        Instant now = Instant.now();
        for (ToolCall call : calls) {
            run.addStep(RunStep.toolPending(UUID.randomUUID().toString(), call.id(), call.name(), now,
                    taskId, workerId).finish(RunStepStatus.FAILED, now, category, null, null));
        }
        saveMessages(run, taskId, messages);
        persist(run);
    }

    private ModelResponse callModel(List<Message> messages, List<com.example.agent.model.ToolDefinition> tools,
                                    RunRecord run, Control control) throws WorkflowAbort, ModelClientException {
        FutureTask<ModelResponse> task = new FutureTask<>(() -> model.complete(List.copyOf(messages), tools));
        control.registerModelTask(task);
        Thread.ofVirtual().name("agent-model-" + run.runId()).start(task);
        try {
            while (true) {
                checkCancelled(run, control);
                checkDeadline(run);
                long wait = Math.min(100, Math.max(1, remaining(run).toMillis()));
                try {
                    return task.get(wait, TimeUnit.MILLISECONDS);
                } catch (TimeoutException pollTimeout) {
                    // Recheck durable cancellation and the total Run deadline while the
                    // provider request is still pending; Future cancellation is best effort.
                }
            }
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            if (control.leaseLost) throw new LeaseLostException(run.runId());
            throw new WorkflowAbort(control.cancelRequested ? FailureCategory.CANCELLED : FailureCategory.TASK_DEADLINE);
        } catch (CancellationException cancelled) {
            if (control.leaseLost) throw new LeaseLostException(run.runId());
            throw new WorkflowAbort(control.cancelRequested ? FailureCategory.CANCELLED : FailureCategory.TASK_DEADLINE);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof ModelClientException providerFailure) throw providerFailure;
            throw new ModelClientException(FailureCategory.MODEL_UNKNOWN, false);
        } finally {
            if (!task.isDone()) task.cancel(true);
            control.clearModelTask(task);
        }
    }

    private String summarize(RunRecord run, List<String> missing,
                             Control control, SharedRunBudget budget) throws WorkflowAbort {
        Instant started = Instant.now();
        String stepId = UUID.randomUUID().toString();
        run.addStep(RunStep.workflow(stepId, StepKind.SUMMARY, null, "supervisor", "SUMMARY", started));
        persist(run);
        String actualResults = run.childTasks().stream().map(task -> "taskId=" + task.taskId() + ", status="
                + task.status() + ", result=" + (task.result() == null ? "(no result)" : task.result()))
                .reduce((a, b) -> a + "\n" + b).orElse("");
        String deliveryList = run.taskPlan().deliverables().stream()
                .map(d -> d.deliverableId() + ": " + d.description()).reduce((a, b) -> a + "\n" + b).orElse("");
        try {
            ModelResponse response = invokeModel(run, List.of(Message.system(SUMMARY_PROMPT),
                            Message.user("Original request:\n" + run.userInput() + "\nDeliverables:\n" + deliveryList
                                    + "\nWorker outcomes:\n" + actualResults + "\nMissing deliverables: " + missing)),
                    List.of(), "supervisor", null, Integer.MAX_VALUE, budget, control);
            run.replaceStep(stepId, run.step(stepId).finish(RunStepStatus.SUCCEEDED, Instant.now(), null,
                    null, null));
            persist(run);
            return response.content();
        } catch (ModelClientException failure) {
            finishWorkflowStep(run, stepId, RunStepStatus.FAILED, failure.category());
            throw new WorkflowAbort(failure.category());
        } catch (WorkflowAbort abort) {
            finishWorkflowStep(run, stepId, RunStepStatus.FAILED, abort.category);
            throw abort;
        }
    }

    private static String appendMissing(String summary, List<String> missing) {
        if (missing.isEmpty()) return summary;
        return summary + "\n\nMissing deliverables: " + String.join(", ", missing);
    }

    private List<Message> workerMessages(RunRecord run, PlannedTask task, WorkerSpec worker) {
        List<Map<String, String>> dependencies = new ArrayList<>();
        for (String dependencyId : task.dependencies()) {
            ChildTaskRecord dependency = childTask(run, dependencyId);
            if (dependency.status() != TaskStatus.SUCCEEDED) throw new IllegalStateException("Dependency result is unavailable");
            dependencies.add(Map.of("taskId", dependency.taskId(), "result", dependency.result()));
        }
        String userContext;
        try {
            userContext = json.writeValueAsString(Map.of("parentRequest", run.userInput(),
                    "task", task.description(), "dependencyResults", dependencies));
        } catch (Exception impossible) {
            throw new IllegalStateException("Unable to encode worker context");
        }
        return new ArrayList<>(List.of(Message.system(worker.systemPrompt()), Message.user(userContext)));
    }

    private void awaitWorker(RunRecord run, Future<?> future, Control control) throws WorkflowAbort {
        try {
            long millis = remaining(run).toMillis();
            if (millis <= 0) throw new WorkflowAbort(FailureCategory.TASK_DEADLINE);
            future.get(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new WorkflowAbort(control.cancelRequested ? FailureCategory.CANCELLED : FailureCategory.TASK_DEADLINE);
        } catch (ExecutionException failure) {
            // executeChildTask converts worker failures into child task states; this is an infrastructure failure.
            Throwable cause = failure.getCause();
            if (cause instanceof LeaseLostException leaseLost) throw leaseLost;
            if (cause instanceof java.util.concurrent.CompletionException completion
                    && completion.getCause() instanceof WorkflowAbort abort) throw abort;
            throw new WorkflowAbort(FailureCategory.WORKER_FAILED);
        } catch (CancellationException cancelled) {
            throw new WorkflowAbort(control.cancelRequested ? FailureCategory.CANCELLED : FailureCategory.WORKER_FAILED);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new WorkflowAbort(FailureCategory.TASK_DEADLINE);
        }
    }

    private void markTasksWithFailedDependencies(RunRecord run) {
        for (ChildTaskRecord task : run.childTasks()) {
            if (task.status() != TaskStatus.PENDING && task.status() != TaskStatus.IN_PROGRESS) continue;
            boolean terminalFailure = task.dependencies().stream().map(id -> taskStatus(run, id))
                    .anyMatch(status -> status == TaskStatus.FAILED || status == TaskStatus.SKIPPED
                            || status == TaskStatus.INDETERMINATE);
            if (terminalFailure) {
                run.replaceChildTask(task.taskId(), task.finish(TaskStatus.SKIPPED, null,
                        FailureCategory.WORKER_FAILED, Instant.now()));
                run.addStep(RunStep.workflow(UUID.randomUUID().toString(), StepKind.WORKER,
                        task.taskId(), task.workerId(), "SKIPPED_DEPENDENCY", Instant.now())
                        .finish(RunStepStatus.FAILED, Instant.now(), FailureCategory.WORKER_FAILED, null, null));
                persist(run);
            }
        }
    }

    private void markUnresolvableTasksSkipped(RunRecord run) {
        for (ChildTaskRecord task : run.childTasks()) {
            if (task.status() == TaskStatus.PENDING || task.status() == TaskStatus.IN_PROGRESS) {
                run.replaceChildTask(task.taskId(), task.finish(TaskStatus.SKIPPED, null,
                        FailureCategory.WORKER_FAILED, Instant.now()));
            }
        }
        persist(run);
    }

    private void recoverInterruptedWork(RunRecord run) {
        for (RunStep step : run.steps()) {
            if (step.status() != RunStepStatus.IN_PROGRESS) continue;
            if (step.kind() == StepKind.MODEL) {
                run.replaceStep(step.stepId(), step.finish(RunStepStatus.FAILED, Instant.now(),
                        FailureCategory.MODEL_UNKNOWN, null, null));
                run.markTokenUsageUnknown();
            } else if (step.kind() == StepKind.TOOL) {
                run.replaceStep(step.stepId(), step.finish(RunStepStatus.INDETERMINATE, Instant.now(),
                        FailureCategory.TOOL_TIMEOUT_UNKNOWN, null, null));
                if (step.taskId() != null) {
                    ChildTaskRecord task = childTask(run, step.taskId());
                    appendUnknownToolResult(run, task, step);
                } else {
                    appendUnknownToolResult(run, new ArrayList<>(run.agentMessages()), step);
                }
            } else {
                run.replaceStep(step.stepId(), step.finish(RunStepStatus.FAILED, Instant.now(),
                        FailureCategory.UNKNOWN, null, null));
            }
        }
        persist(run);
    }

    private void appendUnknownToolResult(RunRecord run, ChildTaskRecord task, RunStep step) {
        List<Message> messages = new ArrayList<>(task.messages());
        appendUnknownToolResult(run, messages, step);
        run.replaceChildTask(task.taskId(), task.withMessages(messages));
    }

    private void appendUnknownToolResult(RunRecord run, List<Message> messages, RunStep step) {
        boolean present = messages.stream().anyMatch(message -> message.role() == Message.Role.TOOL
                && step.toolCallId().equals(message.toolCallId()));
        if (!present) {
            messages.add(Message.toolResult(step.toolCallId(), step.toolName(),
                    "{\"error\":\"Previous execution outcome is unknown; tool was not replayed\"}"));
        }
        if (step.taskId() == null) run.setAgentMessages(messages);
    }

    private void executePendingAfterRecovery(RunRecord run, ToolRegistry tools, ToolExecutor executor,
                                             String taskId, String workerId, List<Message> messages,
                                             Control control, SharedRunBudget budget) throws WorkflowAbort {
        // Pending tool steps are executed by replaying the saved assistant tool request before the next model turn.
        List<RunStep> pending = run.steps().stream().filter(step -> step.kind() == StepKind.TOOL
                && step.status() == RunStepStatus.PENDING
                && java.util.Objects.equals(step.taskId(), taskId)).toList();
        if (!pending.isEmpty() && !budget.reserveToolCalls(
                pending.stream().map(RunStep::stepId).toList(), activeTaskLease.get())) {
            throw new WorkflowAbort(FailureCategory.TOOL_BUDGET_EXCEEDED);
        }
        for (RunStep step : pending) {
            ToolCall call = findToolCall(messages, step.toolCallId());
            if (call == null) throw new WorkflowAbort(FailureCategory.UNKNOWN);
            checkCancelled(run, control);
            checkDeadline(run);
            run.replaceStep(step.stepId(), step.start(Instant.now()));
            persist(run);
            Observation stepObservation = telemetry.startStep(run, run.step(step.stepId()), "collaborative");
            com.example.agent.tool.ToolResult result;
            try (var scope = stepObservation.openScope()) {
                ensureCurrentTaskLease(run);
                result = executor.execute(call, run.runId(), step.stepId(), remaining(run));
            } catch (RuntimeException failure) {
                stepObservation.error(failure);
                stepObservation.stop();
                throw failure;
            }
            run.replaceStep(step.stepId(), run.step(step.stepId()).finish(result.outcomeUnknown()
                    ? RunStepStatus.INDETERMINATE : result.success() ? RunStepStatus.SUCCEEDED : RunStepStatus.FAILED,
                    Instant.now(), result.failureCategory(), null, null));
            messages.add(Message.toolResult(result.toolCallId(), result.toolName(), result.asMessageContent()));
            saveMessages(run, taskId, messages);
            persist(run);
            telemetry.finishStep(stepObservation, run.step(step.stepId()), "collaborative");
        }
    }

    private static ToolCall findToolCall(List<Message> messages, String callId) {
        return messages.stream().filter(message -> message.role() == Message.Role.ASSISTANT)
                .flatMap(message -> message.toolCalls().stream()).filter(call -> call.id().equals(callId))
                .findFirst().orElse(null);
    }

    private void settleUnfinishedTasks(RunRecord run, FailureCategory category) {
        Instant now = Instant.now();
        for (RunStep step : run.steps()) {
            if (step.status() == RunStepStatus.IN_PROGRESS) {
                RunStepStatus status = step.kind() == StepKind.TOOL ? RunStepStatus.INDETERMINATE : RunStepStatus.FAILED;
                FailureCategory failure = step.kind() == StepKind.TOOL
                        ? FailureCategory.TOOL_TIMEOUT_UNKNOWN : category;
                run.replaceStep(step.stepId(), step.finish(status, now, failure, null, null));
            }
        }
        for (ChildTaskRecord task : run.childTasks()) {
            if (task.status() == TaskStatus.PENDING) {
                run.replaceChildTask(task.taskId(), task.finish(TaskStatus.SKIPPED, null, category, now));
            } else if (task.status() == TaskStatus.IN_PROGRESS) {
                boolean taskToolInProgress = run.steps().stream().anyMatch(step -> task.taskId().equals(step.taskId())
                        && step.kind() == StepKind.TOOL && step.status() == RunStepStatus.INDETERMINATE);
                run.replaceChildTask(task.taskId(), task.finish(taskToolInProgress ? TaskStatus.INDETERMINATE
                        : TaskStatus.FAILED, null, taskToolInProgress ? FailureCategory.TOOL_TIMEOUT_UNKNOWN : category, now));
            }
        }
        persist(run);
    }

    private RunRecord complete(RunRecord run, Control control, RunStatus status, FailureCategory category) {
        synchronized (control) {
            if (control.cancelRequested && status != RunStatus.CANCELLED) {
                status = RunStatus.CANCELLED;
                category = FailureCategory.CANCELLED;
                settleUnfinishedTasks(run, category);
            }
            synchronized (run) {
                run.transitionTo(status, Instant.now(), category);
                persist(run);
            }
        }
        log.info("runId={} status={} modelCalls={} toolCalls={} durationMs={} resumed={} tokensKnown={}",
                run.runId(), run.status(), run.modelCalls(), run.toolCalls(), run.totalDurationMillis(),
                run.resumed(), run.tokenUsageKnown());
        return run;
    }

    private void finishWorkflowStep(RunRecord run, String stepId, RunStepStatus status, FailureCategory category) {
        RunStep step = run.step(stepId);
        if (step != null && step.status() == RunStepStatus.IN_PROGRESS) {
            run.replaceStep(stepId, step.finish(status, Instant.now(), category, null, null));
        }
    }

    private void saveMessages(RunRecord run, String taskId, List<Message> messages) {
        if (taskId == null) run.setAgentMessages(messages);
        else run.replaceChildTask(taskId, childTask(run, taskId).withMessages(messages));
    }

    private ChildTaskRecord childTask(RunRecord run, String taskId) {
        return run.childTasks().stream().filter(task -> task.taskId().equals(taskId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown child task"));
    }

    private TaskStatus taskStatus(RunRecord run, String id) {
        return run.childTasks().stream().filter(task -> task.taskId().equals(id)).map(ChildTaskRecord::status)
                .findFirst().orElse(null);
    }

    private void validateWorkerToolAllowLists() {
        for (WorkerSpec worker : workers.all()) allTools.restrictedTo(worker.allowedTools());
    }

    private Duration remaining(RunRecord run) {
        Duration value = Duration.between(Instant.now(), run.deadlineAt());
        return value.isNegative() ? Duration.ZERO : value;
    }

    private void checkDeadline(RunRecord run) throws WorkflowAbort {
        if (!Instant.now().isBefore(run.deadlineAt())) throw new WorkflowAbort(FailureCategory.TASK_DEADLINE);
    }

    private void checkCancelled(RunRecord run, Control control) throws WorkflowAbort {
        if (control.leaseLost) throw new LeaseLostException(run.runId());
        if (control.cancelRequested) throw new WorkflowAbort(FailureCategory.CANCELLED);
        try {
            if (store.find(run.runId()).map(RunRecord::cancellationRequested).orElse(false)) {
                control.cancelRequested = true;
                control.cancelActiveWork();
                throw new WorkflowAbort(FailureCategory.CANCELLED);
            }
        } catch (RunStoreUnavailableException temporarilyUnavailable) {
            // Continue only while the independent parent lease heartbeat remains valid.
            log.debug("runId={} cancellationCheck=store_unavailable", run.runId());
        }
    }

    private void persist(RunRecord run) {
        synchronized (run) {
            Instant now = Instant.now();
            run.touch(now);
            Control control = controls.get(run.runId());
            if (control != null && control.leaseLost) throw new LeaseLostException(run.runId());
            LeaseToken lease = currentParentLease(run.runId());
            TaskLeaseToken taskLease = activeTaskLease.get();
            if (taskLease != null) {
                ensureCurrentTaskLease(run, taskLease, lease, now);
                ChildTaskRecord task = childTask(run, taskLease.taskId()).withSteps(run.steps().stream()
                        .filter(step -> taskLease.taskId().equals(step.taskId())).toList());
                run.replaceChildTask(task.taskId(), task);
                if (!store.saveChildTaskFenced(lease, taskLease, task, now)) {
                    log.warn("runId={} taskId={} workerCheckpoint=lease_rejected", run.runId(), task.taskId());
                    throw new LeaseLostException(run.runId());
                }
                return;
            }
            if (lease == null || !store.saveFenced(run, lease, now)) {
                RunRecord current = store.find(run.runId()).orElse(null);
                log.warn("runId={} leaseWriteRejected ownerMatches={} tokenMatches={} expiresAt={} now={} persistedStatus={}",
                        run.runId(), current != null && lease != null && lease.ownerId().equals(current.leaseOwner()),
                        current != null && lease != null && lease.fencingToken() == current.fencingToken(),
                        current == null ? null : current.leaseExpiresAt(), now,
                        current == null ? null : current.status());
                throw new LeaseLostException(run.runId());
            }
            // Supervisor-only repair/projection updates (for example dependency skips or
            // recovery of interrupted child steps) are independently fenced. Stores reject
            // the write while a child still has a valid lease, and reject already-terminal rows.
            for (ChildTaskRecord task : run.childTasks()) {
                store.saveChildTaskFenced(lease, null, task.withSteps(run.steps().stream()
                        .filter(step -> task.taskId().equals(step.taskId())).toList()), now);
            }
        }
    }

    private void refreshChildTaskProjection(RunRecord run) {
        RunRecord latest = store.find(run.runId()).orElseThrow(() -> new LeaseLostException(run.runId()));
        run.setChildTasks(latest.childTasks());
    }

    private void ensureCurrentTaskLease(RunRecord run) {
        TaskLeaseToken taskLease = activeTaskLease.get();
        if (taskLease != null) ensureCurrentTaskLease(run, taskLease, currentParentLease(run.runId()), Instant.now());
    }

    private LeaseToken currentParentLease(String runId) {
        LeaseToken active = activeParentLease.get();
        return active != null && active.runId().equals(runId) ? active : runLeases.get(runId);
    }

    private void ensureCurrentTaskLease(RunRecord run, TaskLeaseToken taskLease, LeaseToken parentLease, Instant now) {
        if (parentLease == null || !store.isChildTaskLeaseCurrent(taskLease, parentLease, now)) {
            throw new LeaseLostException(run.runId());
        }
    }

    private static final class WorkflowAbort extends Exception {
        private final FailureCategory category;
        private WorkflowAbort(FailureCategory category) { this.category = category; }
    }

    private static final class Control {
        private volatile boolean active;
        private volatile boolean cancelRequested;
        private volatile boolean leaseLost;
        private volatile ExecutorService workerExecutor;
        private final Set<FutureTask<?>> modelTasks = ConcurrentHashMap.newKeySet();

        synchronized void cancelActiveWork() {
            modelTasks.forEach(task -> task.cancel(true));
            if (workerExecutor != null) workerExecutor.shutdownNow();
        }
        synchronized void loseLease() {
            leaseLost = true;
            modelTasks.forEach(task -> task.cancel(true));
            if (workerExecutor != null) workerExecutor.shutdownNow();
        }
        void registerModelTask(FutureTask<?> task) {
            modelTasks.add(task);
            if (cancelRequested) task.cancel(true);
        }
        void clearModelTask(FutureTask<?> task) { modelTasks.remove(task); }
    }
}
