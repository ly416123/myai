package com.example.agent.evaluation;

import com.example.agent.workflow.FailureCategory;
import com.example.agent.workflow.RunStatus;

import java.math.BigDecimal;
import java.util.List;

/** Outcome for one fixture; answer is saved only in the access-restricted report for human scoring. */
public record AgentEvaluationCaseResult(String scenarioId, String runId, String status,
                                        FailureCategory failureCategory, boolean completed,
                                        List<String> expectedTools, List<String> actualTools,
                                        boolean toolSelectionMatched, String planRoute, boolean planMatched,
                                        int plannedTaskCount, List<String> plannedWorkers,
                                        List<AgentEvaluationPlanTask> plannedTasks,
                                        int modelCalls, int toolCalls, boolean tokenUsageKnown,
                                        Long promptTokens, Long completionTokens, BigDecimal estimatedCostUsd,
                                        long durationMillis, String answer, Boolean answerAssertionsMatched,
                                        Integer manualRating, List<EvaluationScore> scores) {
    public AgentEvaluationCaseResult(String scenarioId, String runId, String status,
                                    com.example.agent.workflow.FailureCategory failureCategory, boolean completed,
                                    List<String> expectedTools, List<String> actualTools,
                                    boolean toolSelectionMatched, String planRoute, boolean planMatched,
                                    int plannedTaskCount, List<String> plannedWorkers,
                                    List<AgentEvaluationPlanTask> plannedTasks, int modelCalls, int toolCalls,
                                    boolean tokenUsageKnown, Long promptTokens, Long completionTokens,
                                    BigDecimal estimatedCostUsd, long durationMillis, String answer,
                                    Boolean answerAssertionsMatched, Integer manualRating) {
        this(scenarioId, runId, status, failureCategory, completed, expectedTools, actualTools,
                toolSelectionMatched, planRoute, planMatched, plannedTaskCount, plannedWorkers, plannedTasks,
                modelCalls, toolCalls, tokenUsageKnown, promptTokens, completionTokens, estimatedCostUsd,
                durationMillis, answer, answerAssertionsMatched, manualRating, List.of());
    }

    public AgentEvaluationCaseResult {
        expectedTools = List.copyOf(expectedTools);
        actualTools = List.copyOf(actualTools);
        plannedWorkers = List.copyOf(plannedWorkers);
        plannedTasks = List.copyOf(plannedTasks);
        scores = List.copyOf(scores);
    }

    public AgentEvaluationCaseResult withScores(List<EvaluationScore> value) {
        return new AgentEvaluationCaseResult(scenarioId, runId, status, failureCategory, completed,
                expectedTools, actualTools, toolSelectionMatched, planRoute, planMatched, plannedTaskCount,
                plannedWorkers, plannedTasks, modelCalls, toolCalls, tokenUsageKnown, promptTokens,
                completionTokens, estimatedCostUsd, durationMillis, answer, answerAssertionsMatched,
                manualRating, value);
    }
}
