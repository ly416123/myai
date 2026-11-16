package com.example.agent.evaluation;

import java.time.Instant;
import java.util.Map;

public record EvaluationRunRecord(String evaluationId, EvaluationRunStatus status, EvaluationDatasetIdentity dataset,
                                  String gitCommit, String workflowMode, String provider, String model,
                                  Map<String, String> evaluatorVersions, int modelCallBudget, int toolCallBudget,
                                  int maxCases, Instant createdAt, Instant deadlineAt, Instant completedAt,
                                  EvaluationAggregate aggregate, String failureCategory) {
    public EvaluationRunRecord {
        evaluatorVersions = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(evaluatorVersions));
    }
    public static EvaluationRunRecord created(String id, EvaluationDatasetIdentity dataset, String gitCommit,
            String workflow, String provider, String model, Map<String, String> evaluators,
            int modelBudget, int toolBudget, int maxCases, Instant created, Instant deadline) {
        return new EvaluationRunRecord(id, EvaluationRunStatus.CREATED, dataset, gitCommit, workflow, provider, model,
                evaluators, modelBudget, toolBudget, maxCases, created, deadline, null, EvaluationAggregate.empty(), null);
    }
}
