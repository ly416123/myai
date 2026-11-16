package com.example.agent.evaluation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable registry of the deterministic checks available to datasets. */
public final class EvaluationEvaluatorRegistry {
    private final Map<String, EvaluationEvaluator> evaluators;

    public EvaluationEvaluatorRegistry(List<EvaluationEvaluator> registered) {
        var map = new LinkedHashMap<String, EvaluationEvaluator>();
        for (EvaluationEvaluator evaluator : registered) {
            if (evaluator == null || evaluator.id() == null || evaluator.id().isBlank()
                    || evaluator.version() == null || evaluator.version().isBlank()
                    || map.putIfAbsent(evaluator.id(), evaluator) != null) {
                throw new IllegalArgumentException("Invalid or duplicate evaluation evaluator");
            }
        }
        evaluators = Map.copyOf(map);
    }

    public static EvaluationEvaluatorRegistry defaults() {
        return new EvaluationEvaluatorRegistry(List.of(
                new RunOutcomeEvaluator(), new ToolSelectionEvaluator(), new CollaborationPlanEvaluator(),
                new AnswerAssertionEvaluator(), new CallBudgetEvaluator(), new StructuredOutputEvaluator(),
                new ExpectedFailureCategoryEvaluator()));
    }

    public void validate(List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(id -> !evaluators.containsKey(id))) {
            throw new IllegalArgumentException("Evaluation dataset references an unsupported evaluator");
        }
    }

    public Map<String, String> versions() {
        var versions = new java.util.TreeMap<String, String>();
        evaluators.forEach((id, evaluator) -> versions.put(id, evaluator.version()));
        return java.util.Collections.unmodifiableMap(versions);
    }

    public List<EvaluationScore> evaluate(AgentEvaluationScenario scenario, AgentEvaluationCaseResult result,
                                          int modelCallLimit, int toolCallLimit) {
        validate(scenario.evaluatorIds());
        var context = new EvaluationContext(scenario, result, modelCallLimit, toolCallLimit);
        return scenario.evaluatorIds().stream().map(id -> evaluators.get(id).evaluate(context)).toList();
    }

    private record RunOutcomeEvaluator() implements EvaluationEvaluator {
        @Override public String id() { return EvaluationEvaluatorIds.RUN_OUTCOME; }
        @Override public String version() { return "2"; }
        @Override public EvaluationScore evaluate(EvaluationContext context) {
            if (!context.observableRun()) return score(EvaluationOutcome.UNKNOWN, "RUN_NOT_EXECUTED");
            if ("SUCCEEDED".equals(context.result().status())) return score(EvaluationOutcome.PASS, "RUN_SUCCEEDED");
            if ("PARTIAL".equals(context.result().status())) return score(EvaluationOutcome.UNKNOWN, "RUN_PARTIAL");
            return score(EvaluationOutcome.FAIL, "RUN_FAILED");
        }
        private EvaluationScore score(EvaluationOutcome outcome, String reason) {
            return EvaluationScore.of(id(), version(), outcome, reason);
        }
    }

    private record ToolSelectionEvaluator() implements EvaluationEvaluator {
        @Override public String id() { return EvaluationEvaluatorIds.TOOL_SELECTION; }
        @Override public String version() { return "1"; }
        @Override public EvaluationScore evaluate(EvaluationContext context) {
            if (!context.observableRun()) return score(EvaluationOutcome.UNKNOWN, "TOOLS_NOT_OBSERVED");
            boolean matches = context.scenario().expectedTools().stream().sorted().toList()
                    .equals(context.result().actualTools().stream().sorted().toList());
            return score(matches ? EvaluationOutcome.PASS : EvaluationOutcome.FAIL,
                    matches ? "EXPECTED_TOOLS_MATCH" : "EXPECTED_TOOLS_MISMATCH");
        }
        private EvaluationScore score(EvaluationOutcome outcome, String reason) {
            return EvaluationScore.of(id(), version(), outcome, reason);
        }
    }

    private record CollaborationPlanEvaluator() implements EvaluationEvaluator {
        @Override public String id() { return EvaluationEvaluatorIds.COLLABORATION_PLAN; }
        @Override public String version() { return "1"; }
        @Override public EvaluationScore evaluate(EvaluationContext context) {
            var scenario = context.scenario();
            if (scenario.workflow() != EvaluationWorkflow.COLLABORATIVE
                    || scenario.expectedPlanRoute() == null && scenario.expectedWorkers().isEmpty()) {
                return score(EvaluationOutcome.NOT_APPLICABLE, "NO_PLAN_ASSERTION");
            }
            if (!context.observableRun()) return score(EvaluationOutcome.UNKNOWN, "PLAN_NOT_OBSERVED");
            boolean matches = context.result().planMatched();
            return score(matches ? EvaluationOutcome.PASS : EvaluationOutcome.FAIL,
                    matches ? "EXPECTED_PLAN_MATCH" : "EXPECTED_PLAN_MISMATCH");
        }
        private EvaluationScore score(EvaluationOutcome outcome, String reason) {
            return EvaluationScore.of(id(), version(), outcome, reason);
        }
    }

    private record AnswerAssertionEvaluator() implements EvaluationEvaluator {
        @Override public String id() { return EvaluationEvaluatorIds.ANSWER_ASSERTIONS; }
        @Override public String version() { return "1"; }
        @Override public EvaluationScore evaluate(EvaluationContext context) {
            var scenario = context.scenario();
            if (scenario.requiredAnswerPhrases().isEmpty() && scenario.forbiddenAnswerPhrases().isEmpty()) {
                return score(EvaluationOutcome.NOT_APPLICABLE, "NO_ANSWER_ASSERTION");
            }
            if (!context.observableRun() || context.result().answerAssertionsMatched() == null) {
                return score(EvaluationOutcome.UNKNOWN, "ANSWER_NOT_OBSERVED");
            }
            boolean matches = context.result().answerAssertionsMatched();
            return score(matches ? EvaluationOutcome.PASS : EvaluationOutcome.FAIL,
                    matches ? "ANSWER_ASSERTIONS_MATCH" : "ANSWER_ASSERTIONS_MISMATCH");
        }
        private EvaluationScore score(EvaluationOutcome outcome, String reason) {
            return EvaluationScore.of(id(), version(), outcome, reason);
        }
    }

    private record CallBudgetEvaluator() implements EvaluationEvaluator {
        @Override public String id() { return EvaluationEvaluatorIds.CALL_BUDGET; }
        @Override public String version() { return "1"; }
        @Override public EvaluationScore evaluate(EvaluationContext context) {
            if (!context.observableRun()) return score(EvaluationOutcome.UNKNOWN, "CALLS_NOT_OBSERVED");
            boolean within = context.result().modelCalls() <= context.modelCallLimit()
                    && context.result().toolCalls() <= context.toolCallLimit();
            return score(within ? EvaluationOutcome.PASS : EvaluationOutcome.FAIL,
                    within ? "CALL_BUDGET_WITHIN_LIMIT" : "CALL_BUDGET_EXCEEDED");
        }
        private EvaluationScore score(EvaluationOutcome outcome, String reason) {
            return EvaluationScore.of(id(), version(), outcome, reason);
        }
    }

    private record StructuredOutputEvaluator() implements EvaluationEvaluator {
        @Override public String id() { return EvaluationEvaluatorIds.STRUCTURED_OUTPUT; }
        @Override public String version() { return "1"; }
        @Override public EvaluationScore evaluate(EvaluationContext context) {
            var schema = context.scenario().responseJsonSchema();
            if (schema == null) return score(EvaluationOutcome.NOT_APPLICABLE, "NO_RESPONSE_SCHEMA");
            if (!context.observableRun() || context.result().answer() == null)
                return score(EvaluationOutcome.UNKNOWN, "RESPONSE_NOT_OBSERVED");
            boolean valid = EvaluationJsonSchema.matches(schema, context.result().answer());
            return score(valid ? EvaluationOutcome.PASS : EvaluationOutcome.FAIL,
                    valid ? "STRUCTURED_OUTPUT_VALID" : "STRUCTURED_OUTPUT_INVALID");
        }
        private EvaluationScore score(EvaluationOutcome outcome, String reason) {
            return EvaluationScore.of(id(), version(), outcome, reason);
        }
    }

    private record ExpectedFailureCategoryEvaluator() implements EvaluationEvaluator {
        @Override public String id() { return EvaluationEvaluatorIds.EXPECTED_FAILURE_CATEGORY; }
        @Override public String version() { return "1"; }
        @Override public EvaluationScore evaluate(EvaluationContext context) {
            String expected = context.scenario().expectedFailureCategory();
            if (expected == null) return score(EvaluationOutcome.NOT_APPLICABLE, "NO_FAILURE_ASSERTION");
            if (!context.observableRun()) return score(EvaluationOutcome.UNKNOWN, "FAILURE_NOT_OBSERVED");
            String actual = context.result().failureCategory() == null ? null : context.result().failureCategory().name();
            boolean matches = expected.equals(actual);
            return score(matches ? EvaluationOutcome.PASS : EvaluationOutcome.FAIL,
                    matches ? "EXPECTED_FAILURE_CATEGORY_MATCH" : "EXPECTED_FAILURE_CATEGORY_MISMATCH");
        }
        private EvaluationScore score(EvaluationOutcome outcome, String reason) {
            return EvaluationScore.of(id(), version(), outcome, reason);
        }
    }
}
