package com.example.agent.evaluation;

import com.example.agent.workflow.FailureCategory;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;

class DefaultEvaluationEvaluatorsTest {
    private final EvaluationEvaluatorRegistry registry = EvaluationEvaluatorRegistry.defaults();

    @Test
    void scoresRunToolsAndCallBudgetWhileMarkingUnconfiguredChecksNotApplicable() {
        var scenario = new AgentEvaluationScenario("case", EvaluationWorkflow.SINGLE_AGENT,
                "synthetic prompt", List.of("calculator"));
        var result = result("run-1", "SUCCEEDED", true, List.of("calculator"), null, true,
                null, 2, 1, "42", null);

        var scores = registry.evaluate(scenario, result, 5, 3);

        assertEquals(EvaluationOutcome.PASS, score(scores, EvaluationEvaluatorIds.RUN_OUTCOME).outcome());
        assertEquals(EvaluationOutcome.PASS, score(scores, EvaluationEvaluatorIds.TOOL_SELECTION).outcome());
        assertEquals(EvaluationOutcome.PASS, score(scores, EvaluationEvaluatorIds.CALL_BUDGET).outcome());
        assertEquals(EvaluationOutcome.NOT_APPLICABLE,
                score(scores, EvaluationEvaluatorIds.COLLABORATION_PLAN).outcome());
        assertEquals(EvaluationOutcome.NOT_APPLICABLE,
                score(scores, EvaluationEvaluatorIds.ANSWER_ASSERTIONS).outcome());
    }

    @Test
    void recordsUnknownWhenTheCaseDidNotProduceAnObservableRun() {
        var scenario = new AgentEvaluationScenario("case", EvaluationWorkflow.SINGLE_AGENT,
                "synthetic prompt", List.of());
        var result = result(null, "NOT_RUN_MODEL_BUDGET", false, List.of(), null, false,
                null, 0, 0, null, null);

        var scores = registry.evaluate(scenario, result, 1, 0);

        assertEquals(EvaluationOutcome.UNKNOWN, score(scores, EvaluationEvaluatorIds.RUN_OUTCOME).outcome());
        assertEquals(EvaluationOutcome.UNKNOWN, score(scores, EvaluationEvaluatorIds.TOOL_SELECTION).outcome());
        assertEquals(EvaluationOutcome.UNKNOWN, score(scores, EvaluationEvaluatorIds.CALL_BUDGET).outcome());
    }

    @Test
    void scoresPartialRunAsUnknownRatherThanSuccessful() {
        var scenario = new AgentEvaluationScenario("case", EvaluationWorkflow.SINGLE_AGENT,
                "synthetic prompt", List.of());
        var result = result("run-partial", "PARTIAL", true, List.of(), null, false,
                null, 1, 0, null, null);
        assertEquals(EvaluationOutcome.UNKNOWN,
                score(registry.evaluate(scenario, result, 2, 2), EvaluationEvaluatorIds.RUN_OUTCOME).outcome());
    }

    @Test
    void checksStructuredJsonOutputAndExpectedFailureCategory() throws Exception {
        var schema = new ObjectMapper().readTree("{\"type\":\"object\",\"required\":[\"answer\"],"
                + "\"properties\":{\"answer\":{\"type\":\"string\"}},\"additionalProperties\":false}");
        var scenario = new AgentEvaluationScenario("structured", EvaluationWorkflow.SINGLE_AGENT, "synthetic",
                List.of(), null, List.of(), List.of(), List.of(), null, "MODEL_TIMEOUT", schema);
        var valid = result("run-structured", "FAILED", false, List.of(), null, false,
                null, 1, 0, "{\"answer\":\"controlled\"}", null, FailureCategory.MODEL_TIMEOUT);
        var scores = registry.evaluate(scenario, valid, 2, 2);
        assertEquals(EvaluationOutcome.PASS, score(scores, EvaluationEvaluatorIds.STRUCTURED_OUTPUT).outcome());
        assertEquals(EvaluationOutcome.PASS, score(scores, EvaluationEvaluatorIds.EXPECTED_FAILURE_CATEGORY).outcome());
        var invalid = result("run-structured", "FAILED", false, List.of(), null, false,
                null, 1, 0, "not json", null, FailureCategory.MODEL_SERVER);
        var invalidScores = registry.evaluate(scenario, invalid, 2, 2);
        assertEquals(EvaluationOutcome.FAIL, score(invalidScores, EvaluationEvaluatorIds.STRUCTURED_OUTPUT).outcome());
        assertEquals(EvaluationOutcome.FAIL, score(invalidScores, EvaluationEvaluatorIds.EXPECTED_FAILURE_CATEGORY).outcome());
    }

    @Test
    void evaluatesDeclaredCollaborativeRouteAndAnswerAssertions() {
        var scenario = new AgentEvaluationScenario("case", EvaluationWorkflow.COLLABORATIVE,
                "synthetic prompt", List.of(), "DELEGATE", List.of("calculator"),
                List.of("42"), List.of("error"));
        var result = result("run-2", "SUCCEEDED", true, List.of(), "DELEGATE", true,
                Boolean.TRUE, 3, 1, "42", null);

        var scores = registry.evaluate(scenario, result, 5, 3);

        assertEquals(EvaluationOutcome.PASS,
                score(scores, EvaluationEvaluatorIds.COLLABORATION_PLAN).outcome());
        assertEquals(EvaluationOutcome.PASS,
                score(scores, EvaluationEvaluatorIds.ANSWER_ASSERTIONS).outcome());
    }

    @Test
    void validatesEvaluatorSelectionBeforeReturningAnyScores() {
        var scenario = new AgentEvaluationScenario("case", EvaluationWorkflow.SINGLE_AGENT,
                "synthetic prompt", List.of(), null, List.of(), List.of(), List.of(), List.of("unknown"));

        assertThrows(IllegalArgumentException.class, () -> registry.validate(scenario.evaluatorIds()));
    }

    private static EvaluationScore score(List<EvaluationScore> scores, String evaluatorId) {
        return scores.stream().filter(item -> item.evaluatorId().equals(evaluatorId)).findFirst().orElseThrow();
    }

    private static AgentEvaluationCaseResult result(String runId, String status, boolean completed,
            List<String> actualTools, String route, boolean planMatched, Boolean answerMatched,
            int modelCalls, int toolCalls, String answer, Integer manualRating) {
        return result(runId, status, completed, actualTools, route, planMatched, answerMatched,
                modelCalls, toolCalls, answer, manualRating, null);
    }

    private static AgentEvaluationCaseResult result(String runId, String status, boolean completed,
            List<String> actualTools, String route, boolean planMatched, Boolean answerMatched,
            int modelCalls, int toolCalls, String answer, Integer manualRating, FailureCategory failure) {
        return new AgentEvaluationCaseResult("case", runId, status, failure, completed,
                List.of(), actualTools, true, route, planMatched, 0, List.of(), List.of(), modelCalls, toolCalls,
                true, 2L, 1L, null, 10, answer, answerMatched, manualRating);
    }
}
