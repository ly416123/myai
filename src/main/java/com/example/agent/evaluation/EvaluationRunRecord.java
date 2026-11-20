package com.example.agent.evaluation;

import java.time.Instant;
import java.util.Map;

public record EvaluationRunRecord(String evaluationId, EvaluationRunStatus status, EvaluationDatasetIdentity dataset,
                                  String gitCommit, String workflowMode, String provider, String model,
                                  String configurationHash, Map<String, String> evaluatorVersions,
                                  int modelCallBudget, int toolCallBudget,
                                  int maxCases, Instant createdAt, Instant deadlineAt, Instant completedAt,
                                  EvaluationAggregate aggregate, String failureCategory) {
    public EvaluationRunRecord {
        evaluatorVersions = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(evaluatorVersions));
        if (configurationHash != null && !configurationHash.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Evaluation configuration hash is invalid");
        }
    }

    /** Compatibility constructor for evaluations created before configuration-version linkage. */
    public EvaluationRunRecord(String evaluationId, EvaluationRunStatus status, EvaluationDatasetIdentity dataset,
                               String gitCommit, String workflowMode, String provider, String model,
                               Map<String, String> evaluatorVersions, int modelCallBudget, int toolCallBudget,
                               int maxCases, Instant createdAt, Instant deadlineAt, Instant completedAt,
                               EvaluationAggregate aggregate, String failureCategory) {
        this(evaluationId, status, dataset, gitCommit, workflowMode, provider, model, null, evaluatorVersions,
                modelCallBudget, toolCallBudget, maxCases, createdAt, deadlineAt, completedAt, aggregate,
                failureCategory);
    }

    public static EvaluationRunRecord created(String id, EvaluationDatasetIdentity dataset, String gitCommit,
            String workflow, String provider, String model, Map<String, String> evaluators,
            int modelBudget, int toolBudget, int maxCases, Instant created, Instant deadline) {
        return created(id, dataset, gitCommit, workflow, provider, model, null, evaluators,
                modelBudget, toolBudget, maxCases, created, deadline);
    }

    public static EvaluationRunRecord created(String id, EvaluationDatasetIdentity dataset, String gitCommit,
            String workflow, String provider, String model, String configurationHash,
            Map<String, String> evaluators, int modelBudget, int toolBudget, int maxCases,
            Instant created, Instant deadline) {
        return new EvaluationRunRecord(id, EvaluationRunStatus.CREATED, dataset, gitCommit, workflow, provider, model,
                configurationHash, evaluators, modelBudget, toolBudget, maxCases, created, deadline, null,
                EvaluationAggregate.empty(), null);
    }
}
