package com.example.agent.configuration;

import java.util.List;
import java.util.Map;

/** Persistable evidence for a deterministic publication gate decision. */
public record EvaluationGateDecision(String policyVersion, boolean passed, String reason,
                                     String candidateEvaluationId, String baselineEvaluationId,
                                     String configurationHash, String datasetId, String datasetVersion,
                                     String datasetHash, Map<String, String> evaluatorVersions,
                                     int requiredCases, int observedCases, List<String> regressions) {
    public EvaluationGateDecision {
        evaluatorVersions = evaluatorVersions == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(evaluatorVersions));
        regressions = regressions == null ? List.of() : List.copyOf(regressions);
    }
}
