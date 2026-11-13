package com.example.agent.evaluation;

import com.example.agent.client.ModelClient;
import com.example.agent.collaboration.CollaborationConfig;
import com.example.agent.collaboration.CollaborativeWorkflow;
import com.example.agent.collaboration.WorkerRegistry;
import com.example.agent.observability.AgentTelemetry;
import com.example.agent.runtime.AgentRuntime;
import com.example.agent.tool.ToolExecutor;
import com.example.agent.tool.ToolRegistry;
import com.example.agent.workflow.RunRecord;
import com.example.agent.workflow.RunStatus;
import com.example.agent.workflow.RunStep;
import com.example.agent.workflow.RunStore;
import com.example.agent.workflow.RuntimeConfig;
import com.example.agent.workflow.StepKind;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Runs explicitly enabled live-provider cases with aggregate call budgets and a protected local report. */
public final class AgentEvaluationRunner {
    private final ModelClient model;
    private final ToolRegistry tools;
    private final ToolExecutor toolExecutor;
    private final WorkerRegistry workers;
    private final RunStore store;
    private final RuntimeConfig runtimeDefaults;
    private final CollaborationConfig collaborationDefaults;
    private final ObjectMapper mapper;
    private final AgentTelemetry telemetry;

    public AgentEvaluationRunner(ModelClient model, ToolRegistry tools, ToolExecutor toolExecutor,
                                WorkerRegistry workers, RunStore store, RuntimeConfig runtimeDefaults,
                                CollaborationConfig collaborationDefaults, ObjectMapper mapper,
                                AgentTelemetry telemetry) {
        this.model = java.util.Objects.requireNonNull(model);
        this.tools = java.util.Objects.requireNonNull(tools);
        this.toolExecutor = java.util.Objects.requireNonNull(toolExecutor);
        this.workers = java.util.Objects.requireNonNull(workers);
        this.store = java.util.Objects.requireNonNull(store);
        this.runtimeDefaults = java.util.Objects.requireNonNull(runtimeDefaults);
        this.collaborationDefaults = java.util.Objects.requireNonNull(collaborationDefaults);
        this.mapper = java.util.Objects.requireNonNull(mapper);
        this.telemetry = java.util.Objects.requireNonNull(telemetry);
    }

    public AgentEvaluationResult run(AgentEvaluationDataset dataset, String modelName,
                                     AgentEvaluationConfig config) {
        if (dataset == null || modelName == null || modelName.isBlank() || config == null) {
            throw new IllegalArgumentException("Evaluation dataset, model, and limits are required");
        }
        if (!config.enabled()) throw new IllegalStateException("Live model evaluation is disabled");
        Instant startedAt = Instant.now();
        Instant deadline = startedAt.plus(config.totalTimeout());
        List<AgentEvaluationCaseResult> results = new ArrayList<>();
        int usedModels = 0;
        int usedTools = 0;
        int completed = 0;
        int passed = 0;
        boolean allUsageKnown = true;
        long promptTotal = 0;
        long completionTotal = 0;
        BigDecimal costTotal = BigDecimal.ZERO;
        boolean costKnown = config.inputUsdPerMillionTokens() != null;

        for (int i = 0; i < dataset.scenarios().size(); i++) {
            AgentEvaluationScenario scenario = dataset.scenarios().get(i);
            if (i >= config.maxCases()) {
                results.add(notRun(scenario, "NOT_RUN_CASE_LIMIT"));
                continue;
            }
            int remainingModels = config.maxModelCalls() - usedModels;
            int remainingTools = config.maxToolCalls() - usedTools;
            Duration remainingTime = Duration.between(Instant.now(), deadline);
            if (remainingModels < 1) {
                results.add(notRun(scenario, "NOT_RUN_MODEL_BUDGET"));
                continue;
            }
            if (remainingTime.isNegative() || remainingTime.isZero()) {
                results.add(notRun(scenario, "NOT_RUN_TIME_BUDGET"));
                continue;
            }

            Duration caseTimeout = minimum(remainingTime, runtimeDefaults.taskTimeout());
            RunRecord run;
            try {
                run = execute(scenario, remainingModels, remainingTools, caseTimeout);
            } catch (RuntimeException failure) {
                results.add(notRun(scenario, "INFRASTRUCTURE_ERROR"));
                for (int remaining = i + 1; remaining < dataset.scenarios().size(); remaining++) {
                    results.add(notRun(dataset.scenarios().get(remaining), "NOT_RUN_AFTER_ERROR"));
                }
                break;
            }

            usedModels += run.modelCalls();
            usedTools += run.toolCalls();
            completed++;
            List<String> actualTools = run.steps().stream().filter(step -> step.kind() == StepKind.TOOL)
                    .map(RunStep::toolName).filter(java.util.Objects::nonNull).toList();
            boolean completedSuccessfully = run.status() == RunStatus.SUCCEEDED || run.status() == RunStatus.PARTIAL;
            boolean toolsMatch = sameTools(scenario.expectedTools(), actualTools);
            String planRoute = run.taskPlan() == null ? null : run.taskPlan().route().name();
            int plannedTaskCount = run.taskPlan() == null ? 0 : run.taskPlan().tasks().size();
            List<String> plannedWorkers = run.taskPlan() == null ? List.of() : run.taskPlan().tasks().stream()
                    .map(task -> task.workerId()).toList();
            List<AgentEvaluationPlanTask> plannedTasks = run.taskPlan() == null ? List.of() : run.taskPlan().tasks()
                    .stream().map(task -> new AgentEvaluationPlanTask(task.taskId(), task.workerId(),
                            task.dependencies(), task.deliverableIds())).toList();
            boolean planMatches = (scenario.expectedPlanRoute() == null
                    || scenario.expectedPlanRoute().equals(planRoute))
                    && (scenario.expectedWorkers().isEmpty()
                    || sameTools(scenario.expectedWorkers(), plannedWorkers));
            Boolean answerAssertionsMatched = matchAnswerPhrases(scenario, run.finalAnswer());
            Long promptTokens = run.tokenUsageKnown() ? run.promptTokens() : null;
            Long completionTokens = run.tokenUsageKnown() ? run.completionTokens() : null;
            BigDecimal caseCost = estimateCost(promptTokens, completionTokens, config);
            if (run.tokenUsageKnown() && promptTokens != null && completionTokens != null) {
                promptTotal += promptTokens;
                completionTotal += completionTokens;
                if (caseCost != null) costTotal = costTotal.add(caseCost);
            } else {
                allUsageKnown = false;
                costKnown = false;
            }
            boolean passedCase = completedSuccessfully && toolsMatch && planMatches
                    && !Boolean.FALSE.equals(answerAssertionsMatched);
            if (passedCase) passed++;
            results.add(new AgentEvaluationCaseResult(scenario.id(), run.runId(), run.status().name(),
                    run.failureCategory(), completedSuccessfully, scenario.expectedTools(), actualTools, toolsMatch,
                    planRoute, planMatches, plannedTaskCount, plannedWorkers, plannedTasks, run.modelCalls(), run.toolCalls(),
                    run.tokenUsageKnown(), promptTokens, completionTokens, caseCost,
                    run.totalDurationMillis(), run.finalAnswer(), answerAssertionsMatched, null));
        }

        Instant completedAt = Instant.now();
        Long promptTokens = allUsageKnown && completed > 0 ? promptTotal : null;
        Long completionTokens = allUsageKnown && completed > 0 ? completionTotal : null;
        BigDecimal estimatedCost = costKnown && allUsageKnown && completed > 0
                ? costTotal.setScale(8, RoundingMode.HALF_UP) : null;
        Path reportPath = writeReport(dataset.version(), modelName, startedAt, completedAt,
                completed, passed, usedModels, usedTools, allUsageKnown && completed > 0,
                promptTokens, completionTokens, estimatedCost, results, config.reportDirectory());
        return new AgentEvaluationResult(dataset.version(), modelName, startedAt, completedAt, completed, passed,
                usedModels, usedTools, allUsageKnown && completed > 0, promptTokens, completionTokens,
                estimatedCost, results, reportPath);
    }

    public AgentEvaluationResult run(Path datasetPath, String modelName, AgentEvaluationConfig config) {
        return run(AgentEvaluationDatasetLoader.load(datasetPath, mapper), modelName, config);
    }

    private RunRecord execute(AgentEvaluationScenario scenario, int modelBudget, int toolBudget, Duration timeout) {
        if (scenario.workflow() == EvaluationWorkflow.SINGLE_AGENT) {
            RuntimeConfig limits = new RuntimeConfig(modelBudget, toolBudget, runtimeDefaults.maxModelRetries(), timeout);
            try (AgentRuntime runtime = new AgentRuntime(model, tools, toolExecutor, store, limits, telemetry)) {
                return runtime.runTracked(scenario.prompt());
            }
        }
        CollaborationConfig limits = new CollaborationConfig(collaborationDefaults.maxTasks(),
                collaborationDefaults.maxParallelism(), modelBudget, toolBudget,
                collaborationDefaults.maxModelRetries(), timeout, collaborationDefaults.toolTimeout(), false);
        try (CollaborativeWorkflow workflow = new CollaborativeWorkflow(model, tools, workers, store,
                limits, mapper, telemetry)) {
            return workflow.runTracked(scenario.prompt());
        }
    }

    private Path writeReport(String datasetVersion, String modelName, Instant startedAt, Instant completedAt,
                             int completed, int passed, int modelCalls, int toolCalls, boolean usageKnown,
                             Long promptTokens, Long completionTokens, BigDecimal estimatedCost,
                             List<AgentEvaluationCaseResult> results, Path baseDirectory) {
        Path reportDirectory = null;
        try {
            Files.createDirectories(baseDirectory);
            reportDirectory = Files.createTempDirectory(baseDirectory, "evaluation-");
            setPermissions(reportDirectory, "rwx------");
            Path report = reportDirectory.resolve("report.json");
            byte[] content = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(new EvaluationReportFile(
                    datasetVersion, modelName, startedAt, completedAt, completed, passed, modelCalls, toolCalls,
                    usageKnown, promptTokens, completionTokens, estimatedCost, results));
            Files.createFile(report, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(report, content);
            return report;
        } catch (IOException | UnsupportedOperationException failure) {
            if (reportDirectory != null) {
                try (var paths = Files.walk(reportDirectory)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                    });
                } catch (IOException ignored) { }
            }
            throw new IllegalStateException("Unable to write protected evaluation report");
        }
    }

    private static void setPermissions(Path path, String permissions) throws IOException {
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions)); }
        catch (UnsupportedOperationException ignored) { /* Non-POSIX file systems rely on their default ACL. */ }
    }

    private static BigDecimal estimateCost(Long promptTokens, Long completionTokens, AgentEvaluationConfig config) {
        if (promptTokens == null || completionTokens == null || config.inputUsdPerMillionTokens() == null) return null;
        return BigDecimal.valueOf(promptTokens).multiply(config.inputUsdPerMillionTokens())
                .add(BigDecimal.valueOf(completionTokens).multiply(config.outputUsdPerMillionTokens()))
                .divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP);
    }

    private static boolean sameTools(List<String> expected, List<String> actual) {
        return expected.stream().sorted().toList().equals(actual.stream().sorted().toList());
    }

    private static Boolean matchAnswerPhrases(AgentEvaluationScenario scenario, String answer) {
        if (scenario.requiredAnswerPhrases().isEmpty() && scenario.forbiddenAnswerPhrases().isEmpty()) return null;
        String answerText = answer == null ? "" : answer;
        return scenario.requiredAnswerPhrases().stream()
                .allMatch(answerText::contains)
                && scenario.forbiddenAnswerPhrases().stream()
                .noneMatch(answerText::contains);
    }

    private static Duration minimum(Duration left, Duration right) { return left.compareTo(right) <= 0 ? left : right; }

    private static AgentEvaluationCaseResult notRun(AgentEvaluationScenario scenario, String status) {
        return new AgentEvaluationCaseResult(scenario.id(), null, status, null, false,
                scenario.expectedTools(), List.of(), false, null, false, 0, List.of(), List.of(), 0, 0,
                false, null, null, null, 0, null, null, null);
    }

    private record EvaluationReportFile(String datasetVersion, String model, Instant startedAt, Instant completedAt,
                                        int completedCases, int passedCases, int modelCalls, int toolCalls,
                                        boolean tokenUsageKnown, Long promptTokens, Long completionTokens,
                                        BigDecimal estimatedCostUsd, List<AgentEvaluationCaseResult> cases) { }
}
