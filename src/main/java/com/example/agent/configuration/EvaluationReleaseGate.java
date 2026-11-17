package com.example.agent.configuration;

import com.example.agent.evaluation.EvaluationCaseStatus;
import com.example.agent.evaluation.EvaluationComparator;
import com.example.agent.evaluation.EvaluationOutcome;
import com.example.agent.evaluation.EvaluationRunStatus;
import com.example.agent.evaluation.EvaluationEvaluatorIds;
import com.example.agent.evaluation.EvaluationSnapshot;

/** Applies a versioned, deterministic offline-evaluation gate before a config Release may be created. */
public final class EvaluationReleaseGate {
    public static final String POLICY_VERSION = "no-new-regressions-v1";
    private final int minimumCases;
    private final EvaluationComparator comparator = new EvaluationComparator();

    public EvaluationReleaseGate(int minimumCases) {
        if (minimumCases < 1 || minimumCases > 100) throw new IllegalArgumentException("Minimum cases must be 1..100");
        this.minimumCases = minimumCases;
    }

    public EvaluationGateDecision evaluate(EvaluationSnapshot candidate, EvaluationSnapshot baseline,
                                            String expectedConfigurationHash) {
        if (candidate == null || expectedConfigurationHash == null
                || !expectedConfigurationHash.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Candidate evaluation and configuration hash are required");
        }
        var run = candidate.run();
        String reason = null;
        java.util.List<String> regressions = java.util.List.of();
        if (run.status() != EvaluationRunStatus.SUCCEEDED) reason = "EVALUATION_NOT_SUCCEEDED";
        else if (!expectedConfigurationHash.equals(run.configurationHash())) reason = "CONFIG_HASH_MISMATCH";
        else if (run.aggregate().totalCases() < minimumCases) reason = "EVALUATION_SAMPLE_TOO_SMALL";
        else if (run.aggregate().completedCases() != run.aggregate().totalCases()
                || candidate.cases().size() != run.aggregate().totalCases()
                || candidate.cases().stream().anyMatch(c -> c.status() != EvaluationCaseStatus.SUCCEEDED)) {
            reason = "EVALUATION_INCOMPLETE";
        } else if (candidate.cases().stream().anyMatch(c -> c.scores().stream()
                .filter(score -> EvaluationEvaluatorIds.RUN_OUTCOME.equals(score.evaluatorId()))
                .noneMatch(score -> score.outcome() == EvaluationOutcome.PASS
                        || score.outcome() == EvaluationOutcome.NOT_APPLICABLE))) {
            reason = "REQUIRED_EVALUATOR_FAILED";
        } else if (candidate.cases().stream().flatMap(c -> c.scores().stream())
                .anyMatch(score -> score.outcome() == EvaluationOutcome.UNKNOWN)) {
            reason = "EVALUATION_HAS_UNKNOWN_OUTCOMES";
        } else if (baseline == null && candidate.cases().stream().flatMap(c -> c.scores().stream())
                .anyMatch(score -> score.outcome() == EvaluationOutcome.FAIL)) {
            reason = "EVALUATION_HAS_FAILURES";
        }
        if (reason == null && baseline != null) {
            if (baseline.run().status() != EvaluationRunStatus.SUCCEEDED) reason = "BASELINE_NOT_SUCCEEDED";
            else {
                var comparison = comparator.compare(baseline, candidate);
                if (!comparison.comparable()) reason = "EVALUATIONS_INCOMPATIBLE";
                else if (!comparison.regressions().isEmpty()) {
                    reason = "EVALUATOR_REGRESSION";
                    regressions = comparison.regressions();
                }
            }
        }
        return new EvaluationGateDecision(POLICY_VERSION, reason == null, reason == null ? "PASS" : reason,
                run.evaluationId(), baseline == null ? null : baseline.run().evaluationId(), expectedConfigurationHash,
                run.dataset().datasetId(), run.dataset().datasetVersion(), run.dataset().contentHash(),
                run.evaluatorVersions(), minimumCases, run.aggregate().totalCases(), regressions);
    }
}
