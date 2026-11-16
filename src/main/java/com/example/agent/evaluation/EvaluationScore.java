package com.example.agent.evaluation;

import java.math.BigDecimal;

/** Versioned, machine-readable result from one deterministic evaluator. */
public record EvaluationScore(String evaluatorId, String evaluatorVersion, EvaluationOutcome outcome,
                              BigDecimal numericValue, String reasonCode) {
    public EvaluationScore {
        if (evaluatorId == null || evaluatorId.isBlank() || evaluatorVersion == null || evaluatorVersion.isBlank()
                || outcome == null || reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("Invalid evaluation score");
        }
        if ((outcome == EvaluationOutcome.PASS && !BigDecimal.ONE.equals(numericValue))
                || (outcome == EvaluationOutcome.FAIL && !BigDecimal.ZERO.equals(numericValue))
                || ((outcome == EvaluationOutcome.UNKNOWN || outcome == EvaluationOutcome.NOT_APPLICABLE)
                && numericValue != null)) {
            throw new IllegalArgumentException("Evaluation score value does not match its outcome");
        }
    }

    public static EvaluationScore of(String id, String version, EvaluationOutcome outcome, String reasonCode) {
        BigDecimal value = switch (outcome) {
            case PASS -> BigDecimal.ONE;
            case FAIL -> BigDecimal.ZERO;
            case UNKNOWN, NOT_APPLICABLE -> null;
        };
        return new EvaluationScore(id, version, outcome, value, reasonCode);
    }
}
