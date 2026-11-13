package com.example.agent.evaluation;

import java.util.List;

/** One sanitized, version-controlled prompt and its expected tool selection. */
public record AgentEvaluationScenario(String id, EvaluationWorkflow workflow, String prompt,
                                      List<String> expectedTools, String expectedPlanRoute,
                                      List<String> expectedWorkers) {
    public AgentEvaluationScenario(String id, EvaluationWorkflow workflow, String prompt, List<String> expectedTools) {
        this(id, workflow, prompt, expectedTools, null, List.of());
    }

    public AgentEvaluationScenario {
        if (id == null || !id.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")
                || workflow == null || prompt == null || prompt.isBlank() || prompt.length() > 4000) {
            throw new IllegalArgumentException("Invalid evaluation scenario");
        }
        expectedTools = expectedTools == null ? List.of() : List.copyOf(expectedTools);
        expectedWorkers = expectedWorkers == null ? List.of() : List.copyOf(expectedWorkers);
        if (expectedTools.stream().anyMatch(name -> name == null || !name.matches("[a-zA-Z0-9_-]{1,80}"))) {
            throw new IllegalArgumentException("Invalid expected tool name");
        }
        if (expectedPlanRoute != null && java.util.Arrays.stream(
                com.example.agent.collaboration.TaskPlan.Route.values()).noneMatch(route -> route.name().equals(expectedPlanRoute))) {
            throw new IllegalArgumentException("Invalid expected plan route");
        }
        if (expectedWorkers.stream().anyMatch(name -> name == null || !name.matches("[a-zA-Z0-9_-]{1,80}"))) {
            throw new IllegalArgumentException("Invalid expected worker name");
        }
    }
}
