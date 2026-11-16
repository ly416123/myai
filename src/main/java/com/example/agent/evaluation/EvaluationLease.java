package com.example.agent.evaluation;

public record EvaluationLease(String evaluationId, String owner, long fence) {
    public EvaluationLease {
        if (evaluationId == null || owner == null || owner.isBlank() || fence < 1)
            throw new IllegalArgumentException("Invalid evaluation lease");
    }
}
