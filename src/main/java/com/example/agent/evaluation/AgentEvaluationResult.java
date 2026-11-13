package com.example.agent.evaluation;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Persistable output of one bounded evaluation batch. */
public record AgentEvaluationResult(String datasetVersion, String model, Instant startedAt, Instant completedAt,
                                    int completedCases, int passedCases, int modelCalls, int toolCalls,
                                    boolean tokenUsageKnown, Long promptTokens, Long completionTokens,
                                    BigDecimal estimatedCostUsd, List<AgentEvaluationCaseResult> cases,
                                    Path reportPath) {
    public AgentEvaluationResult { cases = List.copyOf(cases); }
    public int notRunCases() { return (int) cases.stream().filter(result -> result.runId() == null).count(); }
}
