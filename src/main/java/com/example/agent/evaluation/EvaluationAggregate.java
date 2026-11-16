package com.example.agent.evaluation;

import java.math.BigDecimal;

public record EvaluationAggregate(int totalCases, int completedCases, int passedCases, int failedCases,
                                 int modelCalls, int toolCalls, Long promptTokens, Long completionTokens,
                                 BigDecimal estimatedCostUsd) {
    public EvaluationAggregate {
        if (totalCases < 0 || completedCases < 0 || passedCases < 0 || failedCases < 0 || modelCalls < 0 || toolCalls < 0)
            throw new IllegalArgumentException("Invalid evaluation aggregate");
    }
    public EvaluationAggregate(int totalCases, int completedCases, int passedCases, int failedCases,
                               int modelCalls, int toolCalls, Long promptTokens, Long completionTokens) {
        this(totalCases, completedCases, passedCases, failedCases, modelCalls, toolCalls, promptTokens, completionTokens, null);
    }
    public static EvaluationAggregate empty() { return new EvaluationAggregate(0, 0, 0, 0, 0, 0, null, null, null); }
}
