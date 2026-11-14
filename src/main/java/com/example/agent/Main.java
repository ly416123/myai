package com.example.agent;

import com.example.agent.collaboration.CollaborativeWorkflow;
import com.example.agent.runtime.AgentRuntime;
import com.example.agent.workflow.RunRecord;
import com.example.agent.workflow.RunStatus;
import com.example.agent.workflow.RunStore;
import com.example.agent.workflow.ToolExecutionAudit;
import com.example.agent.workflow.ToolExecutionAuditStore;
import com.example.agent.app.RunSubmissionService;
import com.example.agent.evaluation.AgentEvaluationConfig;
import com.example.agent.evaluation.AgentEvaluationResult;
import com.example.agent.evaluation.AgentEvaluationRunner;
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

    public Main(AgentRuntime runtime, CollaborativeWorkflow collaborative, RunStore runStore,
                AgentProperties properties, RunSubmissionService submissions, ToolExecutionAuditStore toolAuditStore,
                AgentEvaluationRunner evaluationRunner, AgentEvaluationConfig evaluationConfig) {
        this.runtime = runtime;
        this.collaborative = collaborative;
        this.runStore = runStore;
        this.properties = properties;
        this.submissions = submissions;
        this.toolAuditStore = toolAuditStore;
        this.evaluationRunner = evaluationRunner;
        this.evaluationConfig = evaluationConfig;
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
                + "/multi-enqueue <任务> 提交协作任务，/multi-resume <runId> 恢复多 Agent，/exit 结束。\n");
        try (var scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("你> ");
                if (!scanner.hasNextLine()) break;
                String input = scanner.nextLine().trim();
                if (input.equalsIgnoreCase("/exit") || input.equalsIgnoreCase("/quit")) break;
                if (input.isEmpty()) continue;
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
                        printQueuedRun(submissions.submitSingle(input.substring("/enqueue ".length())));
                    } else if (input.startsWith("/multi-enqueue ")) {
                        printQueuedRun(submissions.submitCollaborative(input.substring("/multi-enqueue ".length())));
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
            AgentEvaluationResult result = evaluationRunner.run(properties.getEvaluationDataset(),
                    properties.getModel(), evaluationConfig);
            System.out.printf("[evaluation dataset=%s model=%s cases=%d/%d passed=%d modelCalls=%d toolCalls=%d "
                            + "tokens=%s estimatedCostUsd=%s report=%s]%n", result.datasetVersion(), result.model(),
                    result.completedCases(), result.cases().size(), result.passedCases(), result.modelCalls(),
                    result.toolCalls(), result.tokenUsageKnown()
                            ? "prompt=" + result.promptTokens() + ",completion=" + result.completionTokens() : "unknown",
                    result.estimatedCostUsd() == null ? "unknown" : result.estimatedCostUsd(), result.reportPath());
            if (result.passedCases() != result.cases().size()) {
                throw new EvaluationExitException("Evaluation did not meet all fixture expectations");
            }
        } catch (EvaluationExitException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new EvaluationExitException("Evaluation could not be completed");
        }
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
