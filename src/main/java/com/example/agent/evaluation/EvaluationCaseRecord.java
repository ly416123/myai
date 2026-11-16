package com.example.agent.evaluation;

import java.time.Instant;
import java.util.List;

public record EvaluationCaseRecord(String evaluationId, String caseId, EvaluationCaseStatus status, String runId,
                                   String failureCategory, List<EvaluationScore> scores, int modelCalls, int toolCalls,
                                   boolean tokenUsageKnown, Long promptTokens, Long completionTokens,
                                   Long durationMillis, Instant startedAt, Instant completedAt, int attempt) {
    public EvaluationCaseRecord { scores = scores == null ? List.of() : List.copyOf(scores); }
}
