package com.example.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationComparatorTest {
    @Test void comparesCompatibleScoresAndRejectsChangedDatasetHash() {
        var base = snapshot("base", "a".repeat(64), EvaluationOutcome.PASS);
        var candidate = snapshot("candidate", "a".repeat(64), EvaluationOutcome.FAIL);
        var comparator = new EvaluationComparator();
        var result = comparator.compare(base, candidate);
        assertTrue(result.comparable());
        assertEquals(List.of("case-1/answer-assertions"), result.regressions());
        assertEquals(1, result.evaluatorDeltas().get("answer-assertions").baselinePass());
        assertEquals(1, result.evaluatorDeltas().get("answer-assertions").candidateFail());
        assertEquals(5L, result.baselineObservations().medianDurationMillis());
        assertEquals(5L, result.baselineObservations().p95DurationMillis());
        assertFalse(comparator.compare(base, snapshot("other", "b".repeat(64), EvaluationOutcome.FAIL)).comparable());
        var missing = new EvaluationSnapshot(candidate.run(), List.of());
        assertEquals("CASE_SET_MISMATCH", comparator.compare(base, missing).reason());
        var running = snapshotWithStatus("running", "a".repeat(64), EvaluationOutcome.PASS, EvaluationRunStatus.RUNNING);
        assertEquals("RUN_NOT_TERMINAL", comparator.compare(running, candidate).reason());
    }

    @Test void exportsOnlyMetadataAndScores() throws Exception {
        var file = Files.createTempDirectory("eval-export-test").resolve("report.json");
        var snapshot = snapshot("export", "c".repeat(64), EvaluationOutcome.PASS);
        new EvaluationJsonExporter(new ObjectMapper().findAndRegisterModules()).export(snapshot, file);
        String json = Files.readString(file);
        assertTrue(json.contains("datasetId"));
        assertTrue(json.contains("scores"));
        assertFalse(json.contains("private prompt"));
        assertFalse(json.contains("secret answer"));
    }

    private static EvaluationSnapshot snapshot(String id, String hash, EvaluationOutcome outcome) {
        return snapshotWithStatus(id, hash, outcome, EvaluationRunStatus.SUCCEEDED);
    }

    private static EvaluationSnapshot snapshotWithStatus(String id, String hash, EvaluationOutcome outcome,
                                                          EvaluationRunStatus status) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var dataset = new EvaluationDatasetIdentity("fixture", "v1", hash, 1);
        var run = new EvaluationRunRecord(id, status, dataset, "abc", "single", "fake", "test",
                Map.of("answer-assertions", "1"), 4, 4, 1, now, now.plusSeconds(30), now.plusSeconds(1),
                new EvaluationAggregate(1, 1, outcome == EvaluationOutcome.PASS ? 1 : 0,
                        outcome == EvaluationOutcome.FAIL ? 1 : 0, 1, 0, null, null), null);
        var score = EvaluationScore.of("answer-assertions", "1", outcome,
                outcome == EvaluationOutcome.PASS ? "ASSERTIONS_MATCH" : "ASSERTIONS_MISMATCH");
        var record = new EvaluationCaseRecord(id, "case-1", outcome == EvaluationOutcome.PASS
                ? EvaluationCaseStatus.SUCCEEDED : EvaluationCaseStatus.FAILED, "run-1", null, List.of(score),
                1, 0, false, null, null, 5L, now, now.plusMillis(5), 1);
        return new EvaluationSnapshot(run, List.of(record));
    }
}
