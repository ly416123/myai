package com.example.agent.evaluation;

import java.util.HashSet;
import java.util.List;

/** Versioned evaluation fixture set; prompts must be synthetic or approved and de-identified. */
public record AgentEvaluationDataset(String version, List<AgentEvaluationScenario> scenarios) {
    public AgentEvaluationDataset {
        if (version == null || !version.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Invalid evaluation dataset version");
        }
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        if (scenarios.isEmpty() || scenarios.size() > 100) {
            throw new IllegalArgumentException("Evaluation dataset must have between 1 and 100 scenarios");
        }
        var ids = new HashSet<String>();
        if (scenarios.stream().anyMatch(scenario -> !ids.add(scenario.id()))) {
            throw new IllegalArgumentException("Evaluation scenario IDs must be unique");
        }
    }
}
