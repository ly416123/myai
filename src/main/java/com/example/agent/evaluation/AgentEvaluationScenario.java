package com.example.agent.evaluation;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;

/** One sanitized, version-controlled prompt and its expected tool selection. */
public record AgentEvaluationScenario(String id, EvaluationWorkflow workflow, String prompt,
                                      List<String> expectedTools, String expectedPlanRoute,
                                      List<String> expectedWorkers, List<String> requiredAnswerPhrases,
                                      List<String> forbiddenAnswerPhrases, List<String> evaluatorIds,
                                      String expectedFailureCategory, JsonNode responseJsonSchema) {
    public AgentEvaluationScenario(String id, EvaluationWorkflow workflow, String prompt, List<String> expectedTools) {
        this(id, workflow, prompt, expectedTools, null, List.of(), List.of(), List.of(), null, null, null);
    }

    public AgentEvaluationScenario(String id, EvaluationWorkflow workflow, String prompt,
                                   List<String> expectedTools, String expectedPlanRoute,
                                   List<String> expectedWorkers) {
        this(id, workflow, prompt, expectedTools, expectedPlanRoute, expectedWorkers, List.of(), List.of(), null, null, null);
    }

    public AgentEvaluationScenario(String id, EvaluationWorkflow workflow, String prompt,
                                   List<String> expectedTools, String expectedPlanRoute,
                                   List<String> expectedWorkers, List<String> requiredAnswerPhrases,
                                   List<String> forbiddenAnswerPhrases) {
        this(id, workflow, prompt, expectedTools, expectedPlanRoute, expectedWorkers,
                requiredAnswerPhrases, forbiddenAnswerPhrases, null, null, null);
    }

    public AgentEvaluationScenario(String id, EvaluationWorkflow workflow, String prompt, List<String> expectedTools,
            String expectedPlanRoute, List<String> expectedWorkers, List<String> requiredAnswerPhrases,
            List<String> forbiddenAnswerPhrases, List<String> evaluatorIds) {
        this(id, workflow, prompt, expectedTools, expectedPlanRoute, expectedWorkers, requiredAnswerPhrases,
                forbiddenAnswerPhrases, evaluatorIds, null, null);
    }

    public AgentEvaluationScenario {
        if (id == null || !id.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")
                || workflow == null || prompt == null || prompt.isBlank() || prompt.length() > 4000) {
            throw new IllegalArgumentException("Invalid evaluation scenario");
        }
        expectedTools = expectedTools == null ? List.of() : List.copyOf(expectedTools);
        expectedWorkers = expectedWorkers == null ? List.of() : List.copyOf(expectedWorkers);
        requiredAnswerPhrases = validatePhrases(requiredAnswerPhrases, "required");
        forbiddenAnswerPhrases = validatePhrases(forbiddenAnswerPhrases, "forbidden");
        evaluatorIds = evaluatorIds == null || evaluatorIds.isEmpty()
                ? EvaluationEvaluatorIds.defaults() : List.copyOf(evaluatorIds);
        if (evaluatorIds.stream().anyMatch(evaluatorId -> evaluatorId == null
                || !evaluatorId.matches("[a-z0-9][a-z0-9-]{0,63}"))
                || evaluatorIds.stream().distinct().count() != evaluatorIds.size()) {
            throw new IllegalArgumentException("Invalid evaluation evaluator IDs");
        }
        if (expectedTools.stream().anyMatch(name -> name == null || !name.matches("[a-zA-Z0-9_-]{1,80}"))) {
            throw new IllegalArgumentException("Invalid expected tool name");
        }
        if (expectedPlanRoute != null && java.util.Arrays.stream(
                com.example.agent.collaboration.TaskPlan.Route.values()).noneMatch(route -> route.name().equals(expectedPlanRoute))) {
            throw new IllegalArgumentException("Invalid expected plan route");
        }
        if (expectedFailureCategory != null && java.util.Arrays.stream(
                com.example.agent.workflow.FailureCategory.values()).noneMatch(category -> category.name().equals(expectedFailureCategory)))
            throw new IllegalArgumentException("Invalid expected failure category");
        responseJsonSchema = responseJsonSchema == null ? null : responseJsonSchema.deepCopy();
        if (expectedWorkers.stream().anyMatch(name -> name == null || !name.matches("[a-zA-Z0-9_-]{1,80}"))) {
            throw new IllegalArgumentException("Invalid expected worker name");
        }
    }

    private static List<String> validatePhrases(List<String> phrases, String kind) {
        List<String> copy = phrases == null ? List.of() : List.copyOf(phrases);
        if (copy.size() > 20 || copy.stream().anyMatch(phrase -> phrase == null || phrase.isBlank()
                || phrase.length() > 120)) {
            throw new IllegalArgumentException("Invalid " + kind + " answer phrase assertions");
        }
        return copy;
    }
}
