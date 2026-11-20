package com.example.agent.configuration;

import com.example.agent.evaluation.EvaluationAggregate;
import com.example.agent.evaluation.EvaluationCaseRecord;
import com.example.agent.evaluation.EvaluationCaseStatus;
import com.example.agent.evaluation.EvaluationDatasetIdentity;
import com.example.agent.evaluation.EvaluationOutcome;
import com.example.agent.evaluation.EvaluationRunRecord;
import com.example.agent.evaluation.EvaluationRunStatus;
import com.example.agent.evaluation.EvaluationScore;
import com.example.agent.evaluation.EvaluationSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationReleaseGateTest {
    private static final String CONFIG_HASH = "c".repeat(64);

    @Test
    void allowsACompletePassingEvaluationOfTheExactCandidateConfiguration() {
        var decision = new EvaluationReleaseGate(1).evaluate(snapshot("eval-candidate", "candidate-hash",
                CONFIG_HASH, EvaluationRunStatus.SUCCEEDED, EvaluationOutcome.PASS), null, CONFIG_HASH);

        assertTrue(decision.passed());
        assertEquals("PASS", decision.reason());
        assertEquals("a".repeat(64), decision.datasetHash());
        assertEquals("eval-candidate", decision.candidateEvaluationId());
    }

    @Test
    void rejectsEvaluationThatWasNotRunAgainstTheCandidateRevision() {
        var decision = new EvaluationReleaseGate(1).evaluate(snapshot("eval-wrong-config", "candidate-hash",
                "d".repeat(64), EvaluationRunStatus.SUCCEEDED, EvaluationOutcome.PASS), null, CONFIG_HASH);

        assertFalse(decision.passed());
        assertEquals("CONFIG_HASH_MISMATCH", decision.reason());
    }

    @Test
    void rejectsIncompleteOrFailedCandidateEvaluation() {
        var incomplete = new EvaluationReleaseGate(2).evaluate(snapshot("eval-incomplete", "candidate-hash",
                CONFIG_HASH, EvaluationRunStatus.PARTIAL, EvaluationOutcome.PASS), null, CONFIG_HASH);
        var failed = new EvaluationReleaseGate(1).evaluate(snapshot("eval-failed", "candidate-hash",
                CONFIG_HASH, EvaluationRunStatus.SUCCEEDED, EvaluationOutcome.FAIL), null, CONFIG_HASH);

        assertEquals("EVALUATION_NOT_SUCCEEDED", incomplete.reason());
        assertEquals("EVALUATION_HAS_FAILURES", failed.reason());
    }

    @Test
    void rejectsIncompatibleBaselineAndAnyDeterministicRegression() {
        var candidate = snapshot("eval-candidate-regressed", "candidate-hash", CONFIG_HASH,
                EvaluationRunStatus.SUCCEEDED, EvaluationOutcome.FAIL);
        var baseline = snapshot("eval-baseline", "baseline-hash", "b".repeat(64),
                EvaluationRunStatus.SUCCEEDED, EvaluationOutcome.PASS);

        var regression = new EvaluationReleaseGate(1).evaluate(candidate, baseline, CONFIG_HASH);
        var incompatible = new EvaluationReleaseGate(1).evaluate(snapshot("eval-candidate-compatible", "candidate-hash",
                        CONFIG_HASH, EvaluationRunStatus.SUCCEEDED, EvaluationOutcome.PASS),
                snapshot("eval-other-dataset", "baseline-hash", "b".repeat(64),
                        EvaluationRunStatus.SUCCEEDED, EvaluationOutcome.PASS, "e".repeat(64)), CONFIG_HASH);

        assertEquals("EVALUATOR_REGRESSION", regression.reason());
        assertFalse(incompatible.passed());
        assertEquals("EVALUATIONS_INCOMPATIBLE", incompatible.reason());
    }

    private static EvaluationSnapshot snapshot(String id, String model, String configHash,
                                               EvaluationRunStatus status, EvaluationOutcome outcome) {
        return snapshot(id, model, configHash, status, outcome, "a".repeat(64));
    }

    private static EvaluationSnapshot snapshot(String id, String model, String configHash,
                                               EvaluationRunStatus status, EvaluationOutcome outcome,
                                               String datasetHash) {
        Instant now = Instant.parse("2026-11-17T00:00:00Z");
        var aggregate = new EvaluationAggregate(1, 1, outcome == EvaluationOutcome.PASS ? 1 : 0,
                outcome == EvaluationOutcome.FAIL ? 1 : 0, 2, 0, 10L, 5L);
        var run = new EvaluationRunRecord(id, status,
                new EvaluationDatasetIdentity("travel", "v1", datasetHash, 1), "commit", "single", "fake",
                model, configHash, Map.of("answer-assertions", "v1"), 5, 0, 1, now, now.plusSeconds(60), now,
                aggregate, null);
        var requiredScore = EvaluationScore.of("run-outcome", "v1", EvaluationOutcome.PASS, "run completed");
        var qualityScore = EvaluationScore.of("answer-assertions", "v1", outcome, "deterministic fixture");
        var testCase = new EvaluationCaseRecord(id, "case-1", EvaluationCaseStatus.SUCCEEDED, "run-1", null,
                List.of(requiredScore, qualityScore), 2, 0, true, 10L, 5L, 10L, now, now, 1);
        return new EvaluationSnapshot(run, List.of(testCase));
    }
}
