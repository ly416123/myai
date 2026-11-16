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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    public Main(AgentRuntime runtime, CollaborativeWorkflow collaborative, RunStore runStore,
                AgentProperties properties, RunSubmissionService submissions, ToolExecutionAuditStore toolAuditStore,
                AgentEvaluationRunner evaluationRunner, AgentEvaluationConfig evaluationConfig,
                EvaluationService evaluationService, EvaluationRepository evaluationRepository, ObjectMapper mapper) {
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
    }

    @Override
    public void run(ApplicationArguments args) {
        String mode = args.getOptionValues("agent.execution-mode") == null
                ? properties.getExecutionMode()
                : args.getOptionValues("agent.execution-mode").getLast();
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
                + "/eval help 查看评测命令，/exit 结束。\n");
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

    private static void requireArguments(String[] parts, int count) {
        if (parts.length != count) throw new IllegalArgumentException("Invalid evaluation command arguments");
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
