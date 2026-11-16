package com.example.agent.evaluation;

import java.util.List;
import java.util.Map;

public record EvaluationComparison(boolean comparable, String reason, String baselineId, String candidateId,
                                   int baselinePassed, int candidatePassed, int baselineFailed, int candidateFailed,
                                   List<String> regressions, List<String> improvements,
                                   Map<String, EvaluatorDelta> evaluatorDeltas,
                                   EvaluationObservationSummary baselineObservations,
                                   EvaluationObservationSummary candidateObservations) {
    public EvaluationComparison(boolean comparable, String reason, String baselineId, String candidateId,
            int baselinePassed, int candidatePassed, int baselineFailed, int candidateFailed,
            List<String> regressions, List<String> improvements) {
        this(comparable, reason, baselineId, candidateId, baselinePassed, candidatePassed, baselineFailed,
                candidateFailed, regressions, improvements, Map.of(), null, null);
    }
    public EvaluationComparison { regressions = List.copyOf(regressions); improvements = List.copyOf(improvements); }

    public record EvaluatorDelta(int baselinePass, int candidatePass, int baselineFail, int candidateFail,
                                 int baselineUnknown, int candidateUnknown) { }
    public record EvaluationObservationSummary(int toolSelectionPass, int toolSelectionObserved,
                                              int tokenKnownCases, int totalCases,
                                              Long meanDurationMillis, int durationSamples,
                                              Long minDurationMillis, Long medianDurationMillis,
                                              Long p95DurationMillis, Long maxDurationMillis,
                                              int modelCalls, int toolCalls, int runSucceeded,
                                              int runOutcomeObserved, java.math.BigDecimal estimatedCostUsd) { }
}
