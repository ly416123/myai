package com.example.agent;

import com.example.agent.collaboration.CollaborativeWorkflow;
import com.example.agent.runtime.AgentRuntime;
import com.example.agent.workflow.RunRecord;
import com.example.agent.workflow.RunStatus;
import com.example.agent.workflow.RunStore;
import com.example.agent.workflow.ToolExecutionAudit;
import com.example.agent.workflow.ToolExecutionAuditStore;
import com.example.agent.app.CallerContext;
import com.example.agent.app.RunSubmissionService;
import com.example.agent.evaluation.AgentEvaluationConfig;
import com.example.agent.evaluation.AgentEvaluationResult;
import com.example.agent.evaluation.AgentEvaluationRunner;
import com.example.agent.evaluation.EvaluationRepository;
import com.example.agent.evaluation.EvaluationService;
import com.example.agent.evaluation.EvaluationComparator;
import com.example.agent.evaluation.EvaluationJsonExporter;
import com.example.agent.evaluation.AgentEvaluationDatasetLoader;
import com.example.agent.configuration.AgentConfigurationService;
import com.example.agent.configuration.AgentConfigRelease;
import com.example.agent.configuration.AgentConfigRevision;
import com.example.agent.memory.MemoryService;
import com.example.agent.memory.MemoryEntry;
import com.example.agent.memory.MemoryKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Scanner;

/** CLI adapter; dependency creation and external configuration belong to Spring's composition root. */
@Component
@ConditionalOnProperty(prefix = "agent.cli", name = "enabled", havingValue = "true", matchIfMissing = true)
public final class Main implements ApplicationRunner {
    private final AgentRuntime runtime;
    private final CollaborativeWorkflow collaborative;
    private final RunStore runStore;
    private final AgentProperties properties;
    private final RunSubmissionService submissions;
    private final ToolExecutionAuditStore toolAuditStore;
    private final AgentEvaluationRunner evaluationRunner;
    private final AgentEvaluationConfig evaluationConfig;
    private final EvaluationService evaluationService;
    private final EvaluationRepository evaluationRepository;
    private final ObjectMapper mapper;
    private final AgentConfigurationService configurationService;
    private final MemoryService memoryService;

    @Autowired
    public Main(AgentRuntime runtime, CollaborativeWorkflow collaborative, RunStore runStore,
                AgentProperties properties, RunSubmissionService submissions, ToolExecutionAuditStore toolAuditStore,
                AgentEvaluationRunner evaluationRunner, AgentEvaluationConfig evaluationConfig,
                EvaluationService evaluationService, EvaluationRepository evaluationRepository, ObjectMapper mapper,
                AgentConfigurationService configurationService, MemoryService memoryService) {
        this.runtime = runtime;
        this.collaborative = collaborative;
        this.runStore = runStore;
        this.properties = properties;
        this.submissions = submissions;
        this.toolAuditStore = toolAuditStore;
        this.evaluationRunner = evaluationRunner;
        this.evaluationConfig = evaluationConfig;
        this.evaluationService = evaluationService;
        this.evaluationRepository = evaluationRepository;
        this.mapper = mapper;
        this.configurationService = configurationService;
        this.memoryService = memoryService;
    }

    /** Compatibility constructor for CLI adapter unit tests that do not exercise release management. */
    public Main(AgentRuntime runtime, CollaborativeWorkflow collaborative, RunStore runStore,
                AgentProperties properties, RunSubmissionService submissions, ToolExecutionAuditStore toolAuditStore,
                AgentEvaluationRunner evaluationRunner, AgentEvaluationConfig evaluationConfig,
                EvaluationService evaluationService, EvaluationRepository evaluationRepository, ObjectMapper mapper) {
        this(runtime, collaborative, runStore, properties, submissions, toolAuditStore, evaluationRunner,
                evaluationConfig, evaluationService, evaluationRepository, mapper, null, null);
    }

    public Main(AgentRuntime runtime, CollaborativeWorkflow collaborative, RunStore runStore,
                AgentProperties properties, RunSubmissionService submissions, ToolExecutionAuditStore toolAuditStore,
                AgentEvaluationRunner evaluationRunner, AgentEvaluationConfig evaluationConfig,
                EvaluationService evaluationService, EvaluationRepository evaluationRepository, ObjectMapper mapper,
                AgentConfigurationService configurationService) {
        this(runtime, collaborative, runStore, properties, submissions, toolAuditStore, evaluationRunner,
                evaluationConfig, evaluationService, evaluationRepository, mapper, configurationService, null);
    }

    @Override
    public void run(ApplicationArguments args) {
        String mode = args.getOptionValues("agent.execution-mode") == null
                ? properties.getExecutionMode()
                : args.getOptionValues("agent.execution-mode").getLast();
        if (args.containsOption("agent.config-command")) {
            var command = new java.util.ArrayList<String>(args.getOptionValues("agent.config-command"));
            command.addAll(args.getNonOptionArgs());
            executeConfigurationCommand(String.join(" ", command));
            return;
        }
        if ("once".equalsIgnoreCase(mode)) {
            executeOnce(properties.getTask());
            return;
        }
        if ("worker".equalsIgnoreCase(mode)) return;
        if ("eval".equalsIgnoreCase(mode)) {
            executeEvaluation();
            return;
        }
        if (!"interactive".equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException("Invalid agent execution mode");
        }
        System.out.println("Java Agent Workflow 已启动。输入 /runs 查看记录，/tool-audit <runId> 查看工具审计，/resume <runId> 恢复单 Agent，"
                + "/multi <任务> 试用 Supervisor–Worker，/enqueue <任务> 提交后台任务，"
                + "/multi-enqueue <任务> 提交协作任务，/multi-resume <runId> 恢复多 Agent，"
                + "/memory help 管理显式记忆，/eval help 查看评测命令，/config help 管理配置版本，/exit 结束。\n");
        try (var scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("你> ");
                if (!scanner.hasNextLine()) break;
                String input = scanner.nextLine().trim();
                if (input.equalsIgnoreCase("/exit") || input.equalsIgnoreCase("/quit")) break;
                if (input.isEmpty()) continue;
                if (input.equals("/eval") || input.startsWith("/eval ")) {
                    try { executeEvaluationCommand(input.equals("/eval") ? "" : input.substring(6).trim()); }
                    catch (Exception e) { System.err.println("评测操作未完成；请检查命令参数和评测记录。"); }
                    System.out.println();
                    continue;
                }
                if (input.equals("/config") || input.startsWith("/config ")) {
                    try { executeConfigurationCommand(input.equals("/config") ? "" : input.substring(8).trim()); }
                    catch (Exception e) { System.err.println("配置操作未完成；请检查命令参数或门禁结果。"); }
                    System.out.println();
                    continue;
                }
                if (input.equals("/memory") || input.startsWith("/memory ")) {
                    try { executeMemoryCommand(input.equals("/memory") ? "" : input.substring(8).trim()); }
                    catch (Exception e) { System.err.println("记忆操作未完成；请检查命令参数或存储状态。"); }
                    System.out.println();
                    continue;
                }
                if (input.equalsIgnoreCase("/runs")) {
                    runStore.listRecent(20).forEach(Main::printRunSummary);
                    System.out.println();
                    continue;
                }
                if (input.equals("/tool-audit")) {
                    System.out.println("用法：/tool-audit <runId>");
                    System.out.println();
                    continue;
                }
                if (input.startsWith("/tool-audit ")) {
                    try {
                        printToolAudit(toolAuditStore.findToolAudit(input.substring("/tool-audit ".length()).trim()));
                    } catch (Exception e) {
                        System.err.println("无法读取工具审计记录。");
                    }
                    System.out.println();
                    continue;
                }
                try {
                    if (input.startsWith("/resume ")) {
                        printRunResult(runtime.resume(input.substring("/resume ".length()).trim()));
                    } else if (input.startsWith("/multi-resume ")) {
                        printRunResult(collaborative.resume(input.substring("/multi-resume ".length()).trim()));
                    } else if (input.startsWith("/enqueue ")) {
                        printQueuedRun(submissions.submitSingle(
                                input.substring("/enqueue ".length()), CallerContext.localCli()));
                    } else if (input.startsWith("/multi-enqueue ")) {
                        printQueuedRun(submissions.submitCollaborative(
                                input.substring("/multi-enqueue ".length()), CallerContext.localCli()));
                    } else if (input.startsWith("/multi ")) {
                        printRunResult(collaborative.runTracked(input.substring("/multi ".length()).trim()));
                    } else if (input.startsWith("/")) {
                        System.out.println("未知命令。支持 /runs、/tool-audit <runId>、/resume <runId>、"
                                + "/multi <任务>、/enqueue <任务>、/multi-enqueue <任务>、/multi-resume <runId>、/exit。");
                    } else {
                        printRunResult(runtime.runTracked(input));
                    }
                } catch (Exception e) {
                    System.err.println("操作未完成；请用 /runs 查看运行记录。");
                }
                System.out.println();
            }
        }
        System.out.println("已退出。");
    }

    private void executeOnce(String task) {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("AGENT_TASK must be set in once mode");
        }
        try {
            RunRecord result = runtime.runTracked(task.trim());
            printRunResult(result);
            if (result.status() != RunStatus.SUCCEEDED && result.status() != RunStatus.PARTIAL) {
                throw new AgentTaskExitException("Agent task did not complete successfully");
            }
        } catch (AgentTaskExitException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentTaskExitException("Agent task execution failed");
        }
    }

    private void executeEvaluation() {
        if (!properties.isEvaluationEnabled()) {
            throw new IllegalStateException("Set AGENT_EVAL_ENABLED=true to run a live model evaluation");
        }
        try {
            var snapshot = AgentEvaluationDatasetLoader.loadSnapshot(properties.getEvaluationDataset(), mapper);
            var saved = evaluationService.run(snapshot, properties.getModel(), evaluationConfig,
                    "deepseek", "single-or-dataset");
            var result = evaluationRepository.find(saved.evaluationId()).orElseThrow().run();
            System.out.printf("[evaluation dataset=%s model=%s cases=%d/%d passed=%d modelCalls=%d toolCalls=%d "
                            + "tokens=%s estimatedCostUsd=%s evaluationId=%s]%n", result.dataset().datasetVersion(), result.model(),
                    result.aggregate().completedCases(), result.aggregate().totalCases(), result.aggregate().passedCases(),
                    result.aggregate().modelCalls(), result.aggregate().toolCalls(),
                    result.aggregate().promptTokens() == null ? "unknown" : "prompt=" + result.aggregate().promptTokens()
                            + ",completion=" + result.aggregate().completionTokens(),
                    result.aggregate().estimatedCostUsd() == null ? "unknown" : result.aggregate().estimatedCostUsd(),
                    saved.evaluationId());
            if (result.aggregate().passedCases() != result.aggregate().totalCases()) {
                throw new EvaluationExitException("Evaluation did not meet all fixture expectations");
            }
        } catch (EvaluationExitException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new EvaluationExitException("Evaluation could not be completed");
        }
    }

    private void executeEvaluationCommand(String command) {
        String[] parts = command.split("\\s+");
        if (parts.length == 0 || parts[0].isBlank() || parts[0].equals("help")) {
            System.out.println("/eval run | list [limit] | show <id> | compare <baseline> <candidate> | export <id> <path> | recover <id>");
        } else switch (parts[0]) {
            case "run" -> executeEvaluation();
            case "list" -> {
                int limit = parts.length > 1 ? Integer.parseInt(parts[1]) : 20;
                evaluationRepository.list(limit).forEach(run -> System.out.printf("%s %s %s@%s passed=%d/%d model=%s%n",
                        run.evaluationId(), run.status(), run.dataset().datasetId(), run.dataset().datasetVersion(),
                        run.aggregate().passedCases(), run.aggregate().totalCases(), run.model()));
            }
            case "show" -> {
                requireArguments(parts, 2);
                var snapshot = evaluationRepository.find(parts[1]).orElseThrow();
                System.out.printf("evaluationId=%s status=%s leaseExpiresAt=%s dataset=%s@%s hash=%s%n", snapshot.run().evaluationId(),
                        snapshot.run().status(), snapshot.leaseExpiresAt(), snapshot.run().dataset().datasetId(), snapshot.run().dataset().datasetVersion(),
                        snapshot.run().dataset().contentHash());
                snapshot.statusEvents().forEach(event -> System.out.printf("  state=%s at=%s failure=%s%n",
                        event.status(), event.occurredAt(), event.failureCategory()));
                snapshot.cases().forEach(c -> System.out.printf("  %s %s runId=%s failure=%s calls=%d/%d durationMs=%s tokens=%s checks=%s%n",
                        c.caseId(), c.status(), c.runId(), c.failureCategory(), c.modelCalls(), c.toolCalls(), c.durationMillis(),
                        c.tokenUsageKnown() ? "prompt=" + c.promptTokens() + ",completion=" + c.completionTokens() : "unknown",
                        c.scores().stream().map(s -> s.evaluatorId() + "=" + s.outcome()).toList()));
            }
            case "compare" -> {
                requireArguments(parts, 3);
                var comparison = new EvaluationComparator().compare(
                        evaluationRepository.find(parts[1]).orElseThrow(), evaluationRepository.find(parts[2]).orElseThrow());
                System.out.printf("comparable=%s reason=%s pass=%d->%d regressions=%s improvements=%s%n",
                        comparison.comparable(), comparison.reason(), comparison.baselinePassed(), comparison.candidatePassed(),
                        comparison.regressions(), comparison.improvements());
                if (comparison.comparable()) {
                    System.out.println("Evaluator 变化：" + comparison.evaluatorDeltas());
                    System.out.printf("观测值（仅描述本次样本）：baseline=%s candidate=%s%n",
                            comparison.baselineObservations(), comparison.candidateObservations());
                }
            }
            case "export" -> {
                requireArguments(parts, 3);
                Path output = new EvaluationJsonExporter(mapper).export(
                        evaluationRepository.find(parts[1]).orElseThrow(), Path.of(parts[2]));
                System.out.println("已导出脱敏评测元数据和分数：" + output);
            }
            case "recover" -> {
                requireArguments(parts, 2);
                int changed = evaluationService.markInterruptedAsIndeterminate(parts[1]);
                System.out.printf("已将 %d 个未决/未开始用例安全收敛；不会重放模型请求。%n", changed);
            }
            default -> System.out.println("未知评测子命令。输入 /eval help 查看用法。");
        }
    }

    private void executeConfigurationCommand(String command) {
        if (configurationService == null) throw new IllegalStateException("Configuration release management is unavailable");
        String[] parts = command == null || command.isBlank() ? new String[]{"help"} : command.split("\\s+", 5);
        if (parts[0].equals("help")) {
            System.out.println("/config revision <json-file> | eval <revisionId> | release <revisionId> <evaluationId> <baselineReleaseId|-> <notes>");
            System.out.println("/config revisions | releases | diff <revisionA> <revisionB> | current | history | run <runId>");
            System.out.println("/config gate-attempts");
            System.out.println("/config approve <releaseId> <reason> | activate <releaseId> <reason>");
            System.out.println("/config rollout <releaseId> <percent> <reason> | rollback <releaseId> <reason>");
            return;
        }
        String actor = "local-cli";
        switch (parts[0]) {
            case "revision" -> {
                requireArguments(parts, 2);
                try {
                    var document = mapper.readValue(Path.of(parts[1]).toFile(),
                            com.example.agent.configuration.AgentConfigDocument.class);
                    AgentConfigRevision revision = configurationService.createRevision(document,
                            System.getenv("GIT_COMMIT"), actor, java.time.Instant.now());
                    System.out.printf("revisionId=%s sha256=%s%n", revision.revisionId(), revision.contentHash());
                } catch (java.io.IOException invalidDocument) {
                    throw new IllegalArgumentException("Configuration file could not be read or parsed");
                }
            }
            case "eval" -> {
                requireArguments(parts, 2);
                var dataset = AgentEvaluationDatasetLoader.loadSnapshot(properties.getEvaluationDataset(), mapper);
                var revision = configurationService.revision(parts[1]);
                var evaluation = evaluationService.runForConfiguration(dataset, revision, evaluationConfig,
                        "deepseek", "single-or-dataset");
                System.out.printf("evaluationId=%s status=%s configHash=%s cases=%d passed=%d%n",
                        evaluation.evaluationId(), evaluation.status(), evaluation.configurationHash(),
                        evaluation.aggregate().totalCases(), evaluation.aggregate().passedCases());
            }
            case "release" -> {
                requireArguments(parts, 5);
                AgentConfigRevision revision = configurationService.revision(parts[1]);
                var candidate = evaluationRepository.find(parts[2]).orElseThrow(
                        () -> new IllegalArgumentException("Candidate evaluation was not found"));
                AgentConfigRelease baselineRelease = parts[3].equals("-") ? null
                        : configurationService.release(parts[3]);
                var baseline = baselineRelease == null ? null : evaluationRepository.find(baselineRelease.evaluationId())
                        .orElseThrow(() -> new IllegalArgumentException("Baseline evaluation was not found"));
                AgentConfigRelease release = configurationService.createRelease(revision, candidate, baselineRelease,
                        baseline, System.getenv("GIT_COMMIT"), parts[4], actor, java.time.Instant.now());
                System.out.printf("releaseId=%s revisionId=%s sha256=%s evaluationId=%s gate=PASS%n",
                        release.releaseId(), release.revisionId(), release.contentHash(), release.evaluationId());
            }
            case "revisions" -> configurationService.revisions(50).forEach(revision ->
                    System.out.printf("%s sha256=%s model=%s createdBy=%s%n", revision.revisionId(),
                            revision.contentHash(), revision.document().model().model(), "see revision metadata"));
            case "releases" -> configurationService.releases(50).forEach(release ->
                    System.out.printf("%s revision=%s sha256=%s evaluation=%s gate=%s approved=%s%n", release.releaseId(),
                            release.revisionId(), release.contentHash(), release.evaluationId(),
                            release.gateDecision().path("passed").asBoolean(false) ? "PASS" : "REJECTED",
                            configurationService.isApproved(release.releaseId())));
            case "diff" -> {
                requireArguments(parts, 3);
                configurationService.diff(parts[1], parts[2]).forEach(change ->
                        System.out.printf("%s: %s -> %s%n", change.path(), change.before(), change.after()));
            }
            case "current" -> {
                var assignment = configurationService.current(properties.getConfigurationEnvironment(),
                        properties.getConfigurationAgentId());
                if (assignment == null) System.out.println("activeRelease=none");
                else System.out.printf("environment=%s agentId=%s activeRelease=%s candidateRelease=%s candidatePercent=%d version=%d%n",
                        assignment.environment(), assignment.agentId(), assignment.activeReleaseId(),
                        assignment.candidateReleaseId(), assignment.candidatePercent(), assignment.version());
            }
            case "history" -> configurationService.history(properties.getConfigurationEnvironment(),
                    properties.getConfigurationAgentId(), 50).forEach(event ->
                    System.out.printf("eventId=%d action=%s release=%s actor=%s at=%s reason=%s%n", event.eventId(),
                            event.action(), event.releaseId(), event.actor(), event.occurredAt(), event.reason()));
            case "gate-attempts" -> configurationService.gateAttempts(50).forEach(attempt ->
                    System.out.printf("attemptId=%d revision=%s evaluation=%s baseline=%s gate=%s reason=%s actor=%s at=%s%n",
                            attempt.attemptId(), attempt.revisionId(), attempt.evaluationId(), attempt.baselineReleaseId(),
                            attempt.decision().path("passed").asBoolean(false) ? "PASS" : "REJECTED",
                            attempt.decision().path("reason").asText("unknown"), attempt.actor(), attempt.occurredAt()));
            case "run" -> {
                requireArguments(parts, 2);
                var run = runStore.find(parts[1]).orElseThrow(() -> new IllegalArgumentException("Run was not found"));
                var snapshot = run.configurationSnapshot();
                System.out.printf("runId=%s status=%s releaseId=%s revisionId=%s sha256=%s resumed=%s%n", run.runId(),
                        run.status(), snapshot == null ? "unversioned" : snapshot.releaseId(),
                        snapshot == null ? "unknown" : snapshot.revisionId(),
                        snapshot == null ? "unknown" : snapshot.contentHash(), run.resumed());
            }
            case "approve" -> {
                requireMinimumArguments(parts, 3);
                var approval = configurationService.approveRelease(parts[1], actor, joinFrom(parts, 2),
                        java.time.Instant.now());
                System.out.printf("approved=true releaseId=%s actor=%s at=%s%n", approval.releaseId(),
                        approval.actor(), approval.approvedAt());
            }
            case "activate" -> {
                requireMinimumArguments(parts, 3);
                long version = currentAssignmentVersion();
                boolean changed = configurationService.activate(properties.getConfigurationEnvironment(),
                        properties.getConfigurationAgentId(), parts[1], version, actor, joinFrom(parts, 2));
                System.out.printf("activated=%s assignmentVersion=%d%n", changed, changed ? version + 1 : version);
            }
            case "rollout" -> {
                requireMinimumArguments(parts, 4);
                long version = currentAssignmentVersion();
                boolean changed = configurationService.rollout(properties.getConfigurationEnvironment(),
                        properties.getConfigurationAgentId(), parts[1], Integer.parseInt(parts[2]), version, actor, joinFrom(parts, 3));
                System.out.printf("rolloutUpdated=%s assignmentVersion=%d%n", changed, changed ? version + 1 : version);
            }
            case "rollback" -> {
                requireMinimumArguments(parts, 3);
                long version = currentAssignmentVersion();
                boolean changed = configurationService.rollback(properties.getConfigurationEnvironment(),
                        properties.getConfigurationAgentId(), parts[1], version, actor, joinFrom(parts, 2));
                System.out.printf("rolledBack=%s assignmentVersion=%d%n", changed, changed ? version + 1 : version);
            }
            default -> throw new IllegalArgumentException("Unknown config command");
        }
    }

    private void executeMemoryCommand(String command) {
        if (memoryService == null) throw new IllegalStateException("Memory management is unavailable");
        String[] parts = command == null || command.isBlank() ? new String[]{"help"} : command.split("\\s+", 4);
        if (parts[0].equals("help")) {
            System.out.println("/memory list | show <key> | remember <key> <preference|fact> <content>");
            System.out.println("/memory update <key> <preference|fact> <content> | delete <key>");
            return;
        }
        switch (parts[0]) {
            case "list" -> memoryService.list().forEach(Main::printMemoryEntry);
            case "show" -> {
                requireArguments(parts, 2);
                printMemoryEntry(memoryService.find(parts[1]).orElseThrow(MemoryService.MemoryNotFoundException::new));
            }
            case "remember", "update" -> {
                requireArguments(parts, 4);
                MemoryKind kind;
                try { kind = MemoryKind.valueOf(parts[2].toUpperCase(java.util.Locale.ROOT)); }
                catch (IllegalArgumentException invalidKind) { throw new IllegalArgumentException("Memory kind must be preference or fact"); }
                MemoryEntry entry = parts[0].equals("remember")
                        ? memoryService.remember(parts[1], kind, parts[3])
                        : memoryService.update(parts[1], kind, parts[3]);
                System.out.printf("记忆已%s：%s v%d%n", parts[0].equals("remember") ? "保存" : "更新", entry.key(), entry.version());
            }
            case "delete" -> {
                requireArguments(parts, 2);
                memoryService.delete(parts[1]);
                System.out.println("记忆已删除；已存在的 Run 快照按 Run 保留策略保留。");
            }
            default -> System.out.println("未知记忆子命令。输入 /memory help 查看用法。");
        }
    }

    private static void printMemoryEntry(MemoryEntry entry) {
        System.out.printf("%s [%s] v%d: %s%n", entry.key(), entry.kind(), entry.version(), entry.content());
    }

    private long currentAssignmentVersion() {
        var current = configurationService.current(properties.getConfigurationEnvironment(),
                properties.getConfigurationAgentId());
        return current == null ? 0 : current.version();
    }

    private static String joinFrom(String[] parts, int index) {
        if (parts.length <= index) throw new IllegalArgumentException("A change reason is required");
        return String.join(" ", java.util.Arrays.copyOfRange(parts, index, parts.length));
    }

    private static void requireArguments(String[] parts, int count) {
        if (parts.length != count) throw new IllegalArgumentException("Invalid evaluation command arguments");
    }

    private static void requireMinimumArguments(String[] parts, int count) {
        if (parts.length < count) throw new IllegalArgumentException("Invalid configuration command arguments");
    }

    private static final class EvaluationExitException extends RuntimeException implements ExitCodeGenerator {
        private EvaluationExitException(String message) { super(message); }
        @Override public int getExitCode() { return 1; }
    }

    private static final class AgentTaskExitException extends RuntimeException implements ExitCodeGenerator {
        private AgentTaskExitException(String message) { super(message); }
        @Override public int getExitCode() { return 1; }
    }

    private static void printRunResult(RunRecord run) {
        System.out.printf("[runId=%s status=%s modelCalls=%d toolCalls=%d resumed=%s durationMs=%d]%n",
                run.runId(), run.status(), run.modelCalls(), run.toolCalls(), run.resumed(), run.totalDurationMillis());
        if (run.status() == RunStatus.SUCCEEDED || run.status() == RunStatus.PARTIAL) {
            if (run.status() == RunStatus.PARTIAL) System.out.println("Agent（部分结果）>");
            else System.out.print("Agent> ");
            System.out.println(run.finalAnswer());
        } else {
            System.err.println("本轮结束：" + run.failureCategory());
        }
    }

    private static void printRunSummary(RunRecord run) {
        String tokens = run.tokenUsageKnown()
                ? "prompt=" + run.promptTokens() + ", completion=" + run.completionTokens()
                : "unknown";
        System.out.printf("runId=%s type=%s status=%s modelCalls=%d toolCalls=%d durationMs=%d resumed=%s tokens=%s%n",
                run.runId(), run.runType(), run.status(), run.modelCalls(), run.toolCalls(), run.totalDurationMillis(),
                run.resumed(), tokens);
    }

    private static void printQueuedRun(RunRecord run) {
        System.out.printf("[runId=%s type=%s status=%s] 已提交到持久任务队列。%n",
                run.runId(), run.runType(), run.status());
    }

    private static void printToolAudit(java.util.List<ToolExecutionAudit> events) {
        if (events.isEmpty()) {
            System.out.println("没有工具调用审计事件。");
            return;
        }
        for (ToolExecutionAudit event : events) {
            System.out.printf("runId=%s stepId=%s taskId=%s workerId=%s toolCallId=%s tool=%s status=%s "
                            + "failure=%s at=%s%n", event.runId(), event.stepId(), event.taskId(), event.workerId(),
                    event.toolCallId(), event.toolName(), event.status(), event.failureCategory(), event.occurredAt());
        }
    }
}
