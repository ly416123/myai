package com.example.agent.evaluation;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

public final class EvaluationComparator {
    public EvaluationComparison compare(EvaluationSnapshot baseline, EvaluationSnapshot candidate) {
        if (baseline == null || candidate == null) throw new IllegalArgumentException("Both evaluations are required");
        var b = baseline.run(); var c = candidate.run();
        if (b.status() == EvaluationRunStatus.CREATED || b.status() == EvaluationRunStatus.RUNNING
                || c.status() == EvaluationRunStatus.CREATED || c.status() == EvaluationRunStatus.RUNNING)
            return incompatible(b, c, "RUN_NOT_TERMINAL");
        if (!b.dataset().equals(c.dataset())) return incompatible(b, c, "DATASET_IDENTITY_MISMATCH");
        if (!b.evaluatorVersions().equals(c.evaluatorVersions())) return incompatible(b, c, "EVALUATOR_VERSION_MISMATCH");
        if (!b.workflowMode().equals(c.workflowMode())) return incompatible(b, c, "WORKFLOW_MODE_MISMATCH");
        if (!baseline.cases().stream().map(EvaluationCaseRecord::caseId).collect(java.util.stream.Collectors.toSet())
                .equals(candidate.cases().stream().map(EvaluationCaseRecord::caseId).collect(java.util.stream.Collectors.toSet())))
            return incompatible(b, c, "CASE_SET_MISMATCH");
        Map<String, EvaluationScore> before = scores(baseline);
        Map<String, EvaluationScore> after = scores(candidate);
        var regressions = new java.util.ArrayList<String>();
        var improvements = new java.util.ArrayList<String>();
        for (String key : before.keySet()) {
            var old = before.get(key); var now = after.get(key);
            if (now == null) continue;
            boolean oldPass = pass(old); boolean newPass = pass(now);
            if (oldPass && !newPass) regressions.add(key);
            if (!oldPass && newPass) improvements.add(key);
        }
        if (!after.keySet().equals(before.keySet())) return incompatible(b, c, "EVALUATOR_SCORE_SET_MISMATCH");
        var deltas = evaluatorDeltas(before, after);
        return new EvaluationComparison(true, "COMPATIBLE", b.evaluationId(), c.evaluationId(),
                b.aggregate().passedCases(), c.aggregate().passedCases(), b.aggregate().failedCases(),
                c.aggregate().failedCases(), regressions, improvements, deltas,
                summarize(baseline), summarize(candidate));
    }
    private static EvaluationComparison incompatible(EvaluationRunRecord b, EvaluationRunRecord c, String reason) {
        return new EvaluationComparison(false, reason, b.evaluationId(), c.evaluationId(),
                b.aggregate().passedCases(), c.aggregate().passedCases(), b.aggregate().failedCases(),
                c.aggregate().failedCases(), java.util.List.of(), java.util.List.of());
    }
    private static Map<String, EvaluationScore> scores(EvaluationSnapshot s) {
        var map = new HashMap<String, EvaluationScore>();
        s.cases().forEach(c -> c.scores().forEach(score -> map.put(c.caseId() + "/" + score.evaluatorId(), score)));
        return map;
    }
    private static boolean pass(EvaluationScore s) {
        return s.outcome() == EvaluationOutcome.PASS || s.outcome() == EvaluationOutcome.NOT_APPLICABLE;
    }

    private static Map<String, EvaluationComparison.EvaluatorDelta> evaluatorDeltas(
            Map<String, EvaluationScore> before, Map<String, EvaluationScore> after) {
        var beforeCounts = counts(before); var afterCounts = counts(after);
        var ids = new java.util.TreeSet<String>(); ids.addAll(beforeCounts.keySet()); ids.addAll(afterCounts.keySet());
        var result = new java.util.LinkedHashMap<String, EvaluationComparison.EvaluatorDelta>();
        for (String id : ids) {
            int[] b = beforeCounts.getOrDefault(id, new int[4]); int[] c = afterCounts.getOrDefault(id, new int[4]);
            result.put(id, new EvaluationComparison.EvaluatorDelta(b[0], c[0], b[1], c[1], b[2], c[2]));
        }
        return java.util.Collections.unmodifiableMap(result);
    }
    private static Map<String, int[]> counts(Map<String, EvaluationScore> scores) {
        var result = new HashMap<String, int[]>();
        scores.values().forEach(score -> {
            int[] counts = result.computeIfAbsent(score.evaluatorId(), ignored -> new int[4]);
            counts[switch (score.outcome()) {
                case PASS -> 0; case FAIL -> 1; case UNKNOWN -> 2; case NOT_APPLICABLE -> 3;
            }]++;
        });
        return result;
    }
    private static EvaluationComparison.EvaluationObservationSummary summarize(EvaluationSnapshot snapshot) {
        int[] tools = snapshot.cases().stream().flatMap(c -> c.scores().stream())
                .filter(s -> EvaluationEvaluatorIds.TOOL_SELECTION.equals(s.evaluatorId()))
                .collect(() -> new int[2], (counts, score) -> {
                    if (score.outcome() == EvaluationOutcome.PASS) { counts[0]++; counts[1]++; }
                    else if (score.outcome() == EvaluationOutcome.FAIL) counts[1]++;
                }, (left, right) -> { left[0] += right[0]; left[1] += right[1]; });
        int tokenKnown = (int) snapshot.cases().stream().filter(EvaluationCaseRecord::tokenUsageKnown).count();
        int runSuccess = (int) snapshot.cases().stream().flatMap(c -> c.scores().stream())
                .filter(s -> EvaluationEvaluatorIds.RUN_OUTCOME.equals(s.evaluatorId())
                        && s.outcome() == EvaluationOutcome.PASS).count();
        int runObserved = (int) snapshot.cases().stream().flatMap(c -> c.scores().stream())
                .filter(s -> EvaluationEvaluatorIds.RUN_OUTCOME.equals(s.evaluatorId())
                        && (s.outcome() == EvaluationOutcome.PASS || s.outcome() == EvaluationOutcome.FAIL)).count();
        var durations = snapshot.cases().stream().map(EvaluationCaseRecord::durationMillis)
                .filter(java.util.Objects::nonNull).sorted().toList();
        Long mean = durations.isEmpty() ? null : Math.round(durations.stream().mapToLong(Long::longValue).average().orElseThrow());
        Long median = quantile(durations, 0.50);
        Long p95 = quantile(durations, 0.95);
        return new EvaluationComparison.EvaluationObservationSummary(tools[0], tools[1], tokenKnown,
                snapshot.cases().size(), mean, durations.size(), durations.isEmpty() ? null : durations.getFirst(),
                median, p95, durations.isEmpty() ? null : durations.getLast(), snapshot.run().aggregate().modelCalls(),
                snapshot.run().aggregate().toolCalls(), runSuccess, runObserved,
                snapshot.run().aggregate().estimatedCostUsd());
    }
    private static Long quantile(List<Long> sorted, double quantile) {
        if (sorted.isEmpty()) return null;
        int index = Math.max(0, (int) Math.ceil(quantile * sorted.size()) - 1);
        return sorted.get(index);
    }
}
