package com.example.agent.collaboration;

import com.example.agent.client.ModelClient;
import com.example.agent.client.ModelClientException;
import com.example.agent.model.Message;
import com.example.agent.configuration.ActiveConfigurationProvider;
import com.example.agent.configuration.RuntimeSettings;
import com.example.agent.client.ModelRequestOptions;
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
import com.example.agent.memory.MemoryContextProvider;
import com.example.agent.memory.MemoryPrompt;
import io.micrometer.observation.Observation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
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
    private final WorkerContractValidator contractValidator;
    private final AgentTelemetry telemetry;
    private final RunLeasePolicy leasePolicy;
    private final ActiveConfigurationProvider configurationProvider;
    private final String configurationEnvironment;
    private final String configurationAgentId;
    private final com.example.agent.configuration.RunConfigurationSnapshot fixedConfigurationSnapshot;
    private final MemoryContextProvider memoryContextProvider;
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
        this(model, allTools, workers, store, config, json, telemetry, leasePolicy, null, null, null);
    }

    public CollaborativeWorkflow(ModelClient model, ToolRegistry allTools, WorkerRegistry workers,
                                 RunStore store, CollaborationConfig config, ObjectMapper json,
                                 AgentTelemetry telemetry, RunLeasePolicy leasePolicy,
                                 ActiveConfigurationProvider configurationProvider, String configurationEnvironment,
                                 String configurationAgentId) {
        this(model, allTools, workers, store, config, json, telemetry, leasePolicy, configurationProvider,
                configurationEnvironment, configurationAgentId, null, MemoryContextProvider.empty());
    }

    public CollaborativeWorkflow(ModelClient model, ToolRegistry allTools, WorkerRegistry workers,
                                 RunStore store, CollaborationConfig config, ObjectMapper json,
                                 AgentTelemetry telemetry, RunLeasePolicy leasePolicy,
                                 ActiveConfigurationProvider configurationProvider, String configurationEnvironment,
                                 String configurationAgentId,
                                 com.example.agent.configuration.RunConfigurationSnapshot fixedConfigurationSnapshot) {
        this(model, allTools, workers, store, config, json, telemetry, leasePolicy, configurationProvider,
                configurationEnvironment, configurationAgentId, fixedConfigurationSnapshot, MemoryContextProvider.empty());
    }

    public CollaborativeWorkflow(ModelClient model, ToolRegistry allTools, WorkerRegistry workers,
                                 RunStore store, CollaborationConfig config, ObjectMapper json,
                                 AgentTelemetry telemetry, RunLeasePolicy leasePolicy,
                                 ActiveConfigurationProvider configurationProvider, String configurationEnvironment,
                                 String configurationAgentId,
                                 com.example.agent.configuration.RunConfigurationSnapshot fixedConfigurationSnapshot,
                                 MemoryContextProvider memoryContextProvider) {
        this.model = java.util.Objects.requireNonNull(model);
        this.allTools = java.util.Objects.requireNonNull(allTools);
        this.workers = java.util.Objects.requireNonNull(workers);
        this.store = java.util.Objects.requireNonNull(store);
        this.config = java.util.Objects.requireNonNull(config);
        this.json = java.util.Objects.requireNonNull(json);
        this.telemetry = java.util.Objects.requireNonNull(telemetry);
        this.leasePolicy = java.util.Objects.requireNonNull(leasePolicy);
        this.configurationProvider = configurationProvider;
        this.configurationEnvironment = configurationEnvironment;
        this.configurationAgentId = configurationAgentId;
        this.fixedConfigurationSnapshot = fixedConfigurationSnapshot;
        this.memoryContextProvider = java.util.Objects.requireNonNull(memoryContextProvider);
        if (configurationProvider != null && fixedConfigurationSnapshot != null) {
            throw new IllegalArgumentException("Use either active configuration lookup or a fixed configuration snapshot");
        }
        if (configurationProvider != null && (configurationEnvironment == null || configurationEnvironment.isBlank()
                || configurationAgentId == null || configurationAgentId.isBlank())) {
            throw new IllegalArgumentException("Active configuration lookup keys are required");
        }
        this.contractValidator = new WorkerContractValidator(json);
        validateWorkerToolAllowLists();
    }

    public RunRecord runTracked(String input) {
        String runId = UUID.randomUUID().toString();
        var snapshot = fixedConfigurationSnapshot != null ? fixedConfigurationSnapshot
                : configurationProvider == null ? null : configurationProvider.resolve(configurationEnvironment,
                configurationAgentId, runId);
        validatePinnedConfiguration(snapshot);
        RunRecord run = RunRecord.create(runId, input, snapshot == null ? config.timeout()
                : min(config.timeout(), Duration.ofMillis(snapshot.document().runtime().taskTimeoutMillis())), RunType.COLLABORATIVE);
        run.setConfigurationSnapshot(snapshot);
        run.setMemoryContextSnapshot(memoryContextProvider.snapshot(input));
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
                if (configurationProvider != null && run.configurationSnapshot() == null)
                    throw new IllegalStateException("Run has no pinned configuration snapshot and cannot be resumed safely");
                validatePinnedConfiguration(run.configurationSnapshot());
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
        Observation.Scope runScope = runObservation.openScope();
        SharedRunBudget budget = new SharedRunBudget(run, collaborationConfig(run), store, lease);
        try {
            if (run.status() == RunStatus.CREATED) run.transitionTo(RunStatus.RUNNING, Instant.now(), null);
            persist(run);
            log.info("runId={} workflow=SUPERVISOR status=RUNNING resumed={}", run.runId(), recovering);
            if (recovering) recoverInterruptedWork(run);

            if (run.taskPlan() == null) {
                createAndSavePlan(run, control, budget);
            } else if (run.taskPlan().route() == TaskPlan.Route.DELEGATE && run.childTasks().isEmpty()) {
                // 规划结果已落盘、子任务投影尚未落盘时恢复，重新验证后再安全补齐 PENDING 记录。
                validatePersistedPlan(run);
            }
            if (run.taskPlan().route() == TaskPlan.Route.NO_SPLIT) {
                executeSingleAgentFallback(run, control, budget);
                return complete(run, control, RunStatus.SUCCEEDED, null);
            }

            executeTasks(run, control, budget, runObservation);
            checkCancelled(run, control);
            checkDeadline(run);
            List<ChildTaskRecord> successful = run.childTasks().stream()
                    .filter(task -> task.status() == TaskStatus.SUCCEEDED).toList();
            if (successful.isEmpty()) {
                FailureCategory reason = run.childTasks().stream().map(ChildTaskRecord::failureCategory)
                        .filter(category -> category == FailureCategory.WORKER_CONTRACT_MISMATCH)
                        .findFirst().orElse(FailureCategory.NO_SUCCESSFUL_TASKS);
                throw new WorkflowAbort(reason);
            }

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
            runScope.close();
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
        List<Message> plannerMessages = List.of(Message.system(SUPERVISOR_PROMPT),
                Message.user(supervisorRequest(run) + "\n\nUser request:\n" + run.userInput()));
        run.setPlanningInputUtf8Bytes(plannerMessages.stream().map(Message::content)
                .mapToLong(content -> content.getBytes(StandardCharsets.UTF_8).length).sum());
        persist(run);
        ModelResponse response;
        try {
            response = invokeModel(run, plannerMessages,
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
            new TaskPlanValidator(workers, collaborationConfig(run).maxTasks()).validate(plan);
            validatePinnedWorkerContracts(run, plan);
        } catch (Exception invalid) {
            FailureCategory category = FailureCategory.PLAN_INVALID;
            if (invalid instanceof WorkerContractException contractFailure) {
                run.setWorkerContractIssues(contractFailure.issues());
                category = FailureCategory.WORKER_CONTRACT_VIOLATION;
            }
            finishWorkflowStep(run, planStepId, RunStepStatus.FAILED, category);
            throw new WorkflowAbort(category);
        }
        run.replaceStep(planStepId, run.step(planStepId).finish(RunStepStatus.SUCCEEDED, Instant.now(), null,
                null, null));
        if (plan.route() == TaskPlan.Route.DELEGATE) {
            plan = new TaskPlan(plan.route(), plan.tasks().stream().map(task -> task.withWorkerVersion(
                    workers.find(task.workerId()).orElseThrow().version())).toList(), plan.deliverables());
        }
        run.setTaskPlan(plan);
        if (plan.route() == TaskPlan.Route.DELEGATE) {
            Map<String, String> traceContext = telemetry.captureCurrentTraceContext();
            plan.tasks().forEach(task -> run.addChildTask(ChildTaskRecord.pending(task, traceContext)));
        }
        persist(run);
    }

    /** 检查点恢复也重新执行静态契约校验，避免跳过规划阶段的验证边界。 */
    private void validatePersistedPlan(RunRecord run) throws WorkflowAbort {
        try {
            try {
                new TaskPlanValidator(workers, collaborationConfig(run).maxTasks()).validate(run.taskPlan());
                validatePinnedWorkerContracts(run, run.taskPlan());
            } catch (WorkerContractException invalidContract) {
                run.setWorkerContractIssues(invalidContract.issues());
                throw new WorkflowAbort(FailureCategory.WORKER_CONTRACT_VIOLATION);
            }
            if (run.taskPlan().route() != TaskPlan.Route.DELEGATE) return;
            Map<String, ChildTaskRecord> existing = new HashMap<>();
            run.childTasks().forEach(task -> existing.put(task.taskId(), task));
            if (!existing.isEmpty()) return;
            List<String> plannedIds = run.taskPlan().tasks().stream().map(PlannedTask::taskId).toList();
            if (!existing.keySet().stream().allMatch(plannedIds::contains)) throw new IllegalArgumentException();
            Map<String, String> traceContext = telemetry.captureCurrentTraceContext();
            for (PlannedTask planned : run.taskPlan().tasks()) {
                WorkerSpec worker = workers.find(planned.workerId()).orElseThrow(IllegalArgumentException::new);
                if (!java.util.Objects.equals(planned.workerVersion(), worker.version())) {
                    throw new WorkerContractException(List.of(new WorkerContractIssue("*", "WORKER",
                            "WORKER_VERSION_MISMATCH")));
                }
                ChildTaskRecord record = existing.get(planned.taskId());
                if (record == null) {
                    run.addChildTask(ChildTaskRecord.pending(planned, traceContext));
                } else if (!java.util.Objects.equals(record.workerVersion(), worker.version())) {
                    throw new WorkerContractException(List.of(new WorkerContractIssue("*", "WORKER",
                            "WORKER_VERSION_MISMATCH")));
                }
            }
            persist(run);
        } catch (WorkflowAbort abort) {
            throw abort;
        } catch (WorkerContractException mismatch) {
            run.setWorkerContractIssues(mismatch.issues());
            throw new WorkflowAbort(FailureCategory.WORKER_CONTRACT_MISMATCH);
        } catch (RuntimeException invalidPlan) {
            List<WorkerContractIssue> issues = invalidPlan instanceof WorkerContractException contract
                    ? contract.issues()
                    : List.of(new WorkerContractIssue("*", "CHECKPOINT", "PERSISTED_PLAN_INVALID"));
            run.setWorkerContractIssues(issues);
            throw new WorkflowAbort(FailureCategory.WORKER_CONTRACT_VIOLATION);
        }
    }

    private CollaborationConfig collaborationConfig(RunRecord run) {
        if (run.configurationSnapshot() == null) return config;
        RuntimeSettings settings = run.configurationSnapshot().document().runtime();
        return new CollaborationConfig(Math.min(settings.maxTasks(), config.maxTasks()),
                Math.min(settings.maxParallelism(), config.maxParallelism()),
                Math.min(settings.maxModelCalls(), config.maxModelCalls()),
                Math.min(settings.maxToolCalls(), config.maxToolCalls()),
                Math.min(settings.maxModelRetries(), config.maxModelRetries()),
                min(config.timeout(), Duration.ofMillis(settings.taskTimeoutMillis())),
                min(config.toolTimeout(), Duration.ofMillis(settings.toolTimeoutMillis())), config.separateTaskWorkers());
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private ModelRequestOptions modelOptions(RunRecord run) {
        if (run.configurationSnapshot() == null) return null;
        var modelSettings = run.configurationSnapshot().document().model();
        if (!"deepseek".equalsIgnoreCase(modelSettings.provider())) {
            throw new IllegalStateException("Pinned model provider is not supported by this runtime");
        }
        return ModelRequestOptions.from(modelSettings);
    }

    private List<com.example.agent.model.ToolDefinition> modelTools(RunRecord run,
            List<com.example.agent.model.ToolDefinition> offered) {
        if (run.configurationSnapshot() == null) return offered;
        var allowed = run.configurationSnapshot().document().allowedTools();
        return offered.stream().filter(tool -> allowed.contains(tool.name())).toList();
    }

    private List<Message> modelMessages(RunRecord run, List<Message> original, String workerId) {
        boolean supervisor = "supervisor".equals(workerId);
        StringBuilder prompt = new StringBuilder();
        if (run.configurationSnapshot() != null) {
            var document = run.configurationSnapshot().document();
            prompt.append(document.systemPrompt());
            for (var skill : document.skills()) prompt.append("\n\n## Skill: ").append(skill.name())
                    .append("\n").append(skill.content());
        }
        if (supervisor) {
            String memoryPrompt = MemoryPrompt.render(run.memoryContextOrEmpty());
            if (!memoryPrompt.isBlank()) prompt.append("\n\n").append(memoryPrompt);
        }
        if (prompt.isEmpty()) return List.copyOf(original);
        var messages = new ArrayList<Message>();
        messages.add(Message.system(prompt.toString()));
        messages.addAll(original);
        return List.copyOf(messages);
    }

    private boolean configurationToolAllowed(RunRecord run, String toolName) {
        return run.configurationSnapshot() == null
                || run.configurationSnapshot().document().allowedTools().contains(toolName);
    }

    private boolean workerAllowedByPinnedConfig(RunRecord run, WorkerSpec worker) {
        if (run.configurationSnapshot() == null) return true;
        Map<String, String> contractVersions = run.configurationSnapshot().document().workerContractVersions();
        return contractVersions.isEmpty() || java.util.Objects.equals(contractVersions.get(worker.workerId()), worker.version());
    }

    private boolean configurationToolAllowedForRoster(RunRecord run, String toolName) {
        return configurationToolAllowed(run, toolName);
    }

    private void validatePinnedWorkerContracts(RunRecord run, TaskPlan plan) {
        if (run.configurationSnapshot() == null || run.configurationSnapshot().document().workerContractVersions().isEmpty()
                || plan.route() != TaskPlan.Route.DELEGATE) return;
        List<WorkerContractIssue> mismatches = plan.tasks().stream().map(task -> {
            WorkerSpec worker = workers.find(task.workerId()).orElse(null);
            String expected = run.configurationSnapshot().document().workerContractVersions().get(task.workerId());
            return worker != null && java.util.Objects.equals(expected, worker.version()) ? null
                    : new WorkerContractIssue(task.workerId(), "CONFIGURATION", "PINNED_WORKER_VERSION_MISMATCH");
        }).filter(java.util.Objects::nonNull).toList();
        if (!mismatches.isEmpty()) throw new WorkerContractException(mismatches);
    }

    private void validatePinnedConfiguration(com.example.agent.configuration.RunConfigurationSnapshot snapshot) {
        if (snapshot == null) return;
        if (!"deepseek".equalsIgnoreCase(snapshot.document().model().provider()))
            throw new IllegalStateException("Pinned model provider is not supported by this runtime");
        if (snapshot.document().allowedTools().stream().anyMatch(name -> allTools.find(name).isEmpty()))
            throw new IllegalStateException("Pinned configuration references an unregistered tool");
        var mismatched = snapshot.document().workerContractVersions().entrySet().stream().anyMatch(entry ->
                workers.find(entry.getKey()).map(worker -> !java.util.Objects.equals(worker.version(), entry.getValue()))
                        .orElse(true));
        if (mismatched) throw new IllegalStateException("Pinned configuration references an unavailable Worker contract version");
    }

    private String supervisorRequest(RunRecord run) {
        String roster = workers.all().stream().filter(worker -> workerAllowedByPinnedConfig(run, worker)).map(worker -> {
            List<String> offeredTools = worker.allowedTools().stream()
            .filter(name -> configurationToolAllowedForRoster(run, name)).toList();
            if (worker.version() == null) return "- " + worker.workerId() + ": "
                    + worker.responsibility() + "; tools=" + offeredTools;
            JsonNode inputSchema = worker.inputSchema();
            JsonNode properties = inputSchema.path("properties");
            List<String> fields = schemaFieldSummary(inputSchema);
            List<String> outputs = schemaFieldSummary(worker.outputSchema());
            return "- " + worker.workerId() + ": " + worker.planningSummary() + "; inputs=" + fields
                    + "; inputSummary=" + worker.inputSummary() + "; output=" + worker.outputSummary()
                    + "; outputFields=" + outputs
                    + "; tools=" + offeredTools;
        }).reduce((a, b) -> a + "\n" + b).orElse("(no workers registered)");
        return "Registered workers (only these may be selected):\n" + roster
                + "\nMaximum tasks: " + collaborationConfig(run).maxTasks()
                + "\nFor every expected output, add a deliverable and map it to at least one task."
                + "\nEach task must include acceptanceCriteria and inputBindings. For parent values use "
                + "{\"field\":\"...\",\"source\":\"PARENT_REQUEST\",\"parentValue\":...}. "
                + "For dependency fields use {\"field\":\"...\",\"source\":\"DEPENDENCY_OUTPUT\","
                + "\"dependencyTaskId\":\"direct-task-id\",\"jsonPointer\":\"/field\"}. "
                + "Use only declared input fields; bind every required field. Do not include workerVersion.";
    }

    private static List<String> schemaFieldSummary(JsonNode schema) {
        JsonNode properties = schema.path("properties");
        Set<String> required = new HashSet<>();
        schema.path("required").forEach(node -> required.add(node.asText()));
        List<String> fields = new ArrayList<>();
        if (properties.isObject()) properties.fields().forEachRemaining(entry -> fields.add(entry.getKey()
                + ":" + entry.getValue().path("type").asText("any")
                + (required.contains(entry.getKey()) ? " (required)" : "")
                + (entry.getValue().hasNonNull("description")
                ? " — " + entry.getValue().path("description").asText() : "")));
        return fields;
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
        try (ToolExecutor executor = new ToolExecutor(fullRegistry, collaborationConfig(run).toolTimeout())) {
            String answer = runAgentLoop(run, null, "single-agent", messages, fullRegistry, executor,
                    Integer.MAX_VALUE, Integer.MAX_VALUE, control, budget);
            run.setFinalAnswer(answer);
            run.addMessage(Message.assistant(answer));
        }
    }

    private void executeTasks(RunRecord run, Control control, SharedRunBudget budget,
                              Observation runObservation) throws WorkflowAbort {
        if (config.separateTaskWorkers()) {
            awaitSeparateWorkers(run, control);
            return;
        }
        int parallelism = collaborationConfig(run).maxParallelism();
        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
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
            for (int offset = 0; offset < ready.size(); offset += parallelism) {
                checkCancelled(run, control);
                checkDeadline(run);
                if (budget.remainingModelCalls() == 0)
                    throw new WorkflowAbort(FailureCategory.MAX_MODEL_CALLS);
                List<ChildTaskRecord> batch = ready.subList(offset,
                        Math.min(ready.size(), offset + parallelism));
                List<Future<?>> futures = new ArrayList<>();
                for (ChildTaskRecord task : batch) {
                    futures.add(executor.submit(() -> {
                        try (var scope = runObservation.openScope()) {
                            executeChildTask(run, task.taskId(), specs.get(task.taskId()), control, budget);
                        }
                    }));
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
        long graceMillis = Math.min(30_000, collaborationConfig(run).toolTimeout().toMillis() + 1_000);
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
        SharedRunBudget budget = new SharedRunBudget(run, collaborationConfig(run), store, parentLease);
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
        WorkerSpec worker = workers.find(plan.workerId()).orElse(null);
        if (worker == null || !workerAllowedByPinnedConfig(run, worker)
                || !java.util.Objects.equals(task.workerVersion(), worker.version())
                || !java.util.Objects.equals(plan.workerVersion(), worker.version())) {
            ChildTaskRecord failed = task.finish(TaskStatus.FAILED, null,
                    FailureCategory.WORKER_CONTRACT_MISMATCH, Instant.now());
            run.replaceChildTask(taskId, failed);
            run.steps().stream().filter(step -> step.kind() == StepKind.WORKER && taskId.equals(step.taskId())
                            && step.status() == RunStepStatus.IN_PROGRESS)
                    .forEach(step -> run.replaceStep(step.stepId(), step.finish(RunStepStatus.FAILED,
                            Instant.now(), FailureCategory.WORKER_CONTRACT_MISMATCH, null, null)));
            persist(run);
            log.warn("runId={} taskId={} workerContract=version_mismatch", run.runId(), taskId);
            return;
        }
        List<Message> messages = task.messages();
        JsonNode resolvedInput = task.resolvedInput();
        try {
            if (worker.version() != null && resolvedInput == null) {
                Map<String, ChildTaskRecord> dependencies = new HashMap<>();
                for (String dependencyId : task.dependencies()) dependencies.put(dependencyId, childTask(run, dependencyId));
                resolvedInput = contractValidator.resolveInput(worker, plan, run.userInput(), dependencies);
            }
            if (worker.version() != null && resolvedInput == null)
                throw new IllegalArgumentException("Persisted Worker input is unavailable");
            if (task.status() == TaskStatus.PENDING || messages.isEmpty()) {
                messages = workerMessages(run, plan, worker, resolvedInput);
                task = task.start(messages, Instant.now());
                if (resolvedInput != null) task = task.withResolvedInput(resolvedInput);
                run.replaceChildTask(taskId, task);
                run.addStep(RunStep.workflow(UUID.randomUUID().toString(), StepKind.WORKER,
                        taskId, worker.workerId(), "WORKER", task.startedAt()));
                persist(run);
            }
        } catch (RuntimeException invalidInput) {
            if (worker.version() == null) throw invalidInput;
            if (invalidInput instanceof WorkerContractException contractFailure) {
                run.addWorkerContractIssues(contractFailure.issues());
            } else {
                run.addWorkerContractIssues(List.of(new WorkerContractIssue("*", "BINDINGS",
                        "INPUT_RESOLUTION_FAILED")));
            }
            failWorkerContract(run, taskId, worker.workerId(), FailureCategory.WORKER_CONTRACT_VIOLATION);
            return;
        }
        String workerStepId = run.steps().stream().filter(step -> step.kind() == StepKind.WORKER
                        && taskId.equals(step.taskId()) && step.status() == RunStepStatus.IN_PROGRESS)
                .map(RunStep::stepId).findFirst().orElseGet(() -> {
                    RunStep step = RunStep.workflow(UUID.randomUUID().toString(), StepKind.WORKER,
                            taskId, worker.workerId(), "WORKER_RESUME", Instant.now());
                    run.addStep(step);
                    return step.stepId();
                });
        Observation workerObservation;
        try (var remoteParent = telemetry.restoreTraceContext(task.traceContext())) {
            workerObservation = telemetry.startStep(run, run.step(workerStepId), "collaborative");
        }
        ToolRegistry restricted = allTools.restrictedTo(worker.allowedTools());
        try (var workerScope = workerObservation.openScope();
             ToolExecutor executor = new ToolExecutor(restricted, collaborationConfig(run).toolTimeout())) {
            String result = runAgentLoop(run, taskId, worker.workerId(), messages, restricted, executor,
                    worker.maxModelCalls(), worker.maxToolCalls(), control, budget);
            if (result == null || result.isBlank()) throw new ModelClientException(FailureCategory.WORKER_FAILED, false);
            if (worker.version() != null) {
                JsonNode output;
                try {
                    output = json.readTree(result);
                } catch (Exception invalidJson) {
                    run.addWorkerContractIssue(new WorkerContractIssue("*", "WORKER_OUTPUT",
                            "OUTPUT_INVALID_JSON"));
                    throw new ModelClientException(FailureCategory.WORKER_CONTRACT_VIOLATION, false);
                }
                if (!contractValidator.isValidOutput(worker.outputSchema(), output)) {
                    run.addWorkerContractIssue(new WorkerContractIssue("*", "WORKER_OUTPUT",
                            "OUTPUT_SCHEMA_MISMATCH"));
                    throw new ModelClientException(FailureCategory.WORKER_CONTRACT_VIOLATION, false);
                }
                try {
                    result = json.writeValueAsString(output);
                } catch (Exception impossible) {
                    throw new ModelClientException(FailureCategory.WORKER_CONTRACT_VIOLATION, false);
                }
            }
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
        } finally {
            telemetry.finishStep(workerObservation, run.step(workerStepId), "collaborative");
        }
    }

    private void failWorkerContract(RunRecord run, String taskId, String workerId, FailureCategory category) {
        ChildTaskRecord current = childTask(run, taskId).finish(TaskStatus.FAILED, null, category, Instant.now());
        run.replaceChildTask(taskId, current);
        List<RunStep> active = run.steps().stream().filter(step -> step.kind() == StepKind.WORKER
                && taskId.equals(step.taskId()) && step.status() == RunStepStatus.IN_PROGRESS).toList();
        if (active.isEmpty()) {
            run.addStep(RunStep.workflow(UUID.randomUUID().toString(), StepKind.WORKER, taskId,
                    workerId, "WORKER_CONTRACT", Instant.now()).finish(RunStepStatus.FAILED, Instant.now(),
                    category, null, null));
        } else {
            active.forEach(step -> run.replaceStep(step.stepId(), step.finish(RunStepStatus.FAILED,
                    Instant.now(), category, null, null)));
        }
        persist(run);
        log.warn("runId={} taskId={} workerContract=invalid_input", run.runId(), taskId);
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
                        result = configurationToolAllowed(run, call.name())
                                ? toolExecutor.execute(call, run.runId(), pending.stepId(), remaining(run))
                                : com.example.agent.tool.ToolResult.failure(call.id(), call.name(),
                                    "Tool is not allowed by the pinned Run configuration", FailureCategory.TOOL_NOT_ALLOWED);
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
                    response = callModel(messages, tools, run, workerId, control, stepObservation);
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
                if (!failure.retryable() || retries >= collaborationConfig(run).maxModelRetries()
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
                                    RunRecord run, String workerId, Control control, Observation stepObservation)
            throws WorkflowAbort, ModelClientException {
        FutureTask<ModelResponse> task = new FutureTask<>(() -> {
            try (var scope = stepObservation.openScope()) {
                return model.complete(modelMessages(run, messages, workerId), modelTools(run, tools), modelOptions(run));
            }
        });
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

    private List<Message> workerMessages(RunRecord run, PlannedTask task, WorkerSpec worker, JsonNode resolvedInput) {
        if (worker.version() != null) {
            try {
                String context = json.writeValueAsString(Map.of("task", task.description(),
                        "acceptanceCriteria", task.acceptanceCriteria(), "input", resolvedInput));
                String contractPrompt = worker.systemPrompt() + "\nWorker output contract: return exactly one JSON object matching this schema: "
                        + worker.outputSchema();
                return new ArrayList<>(List.of(Message.system(contractPrompt), Message.user(context)));
            } catch (Exception impossible) {
                throw new IllegalStateException("Unable to encode worker contract context");
            }
        }
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
                result = configurationToolAllowed(run, call.name())
                        ? executor.execute(call, run.runId(), step.stepId(), remaining(run))
                        : com.example.agent.tool.ToolResult.failure(call.id(), call.name(),
                            "Tool is not allowed by the pinned Run configuration", FailureCategory.TOOL_NOT_ALLOWED);
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
