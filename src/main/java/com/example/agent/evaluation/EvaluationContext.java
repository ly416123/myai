package com.example.agent.evaluation;

/** Inputs available to a pure evaluator; contains no client or network capability. */
public record EvaluationContext(AgentEvaluationScenario scenario, AgentEvaluationCaseResult result,
                                int modelCallLimit, int toolCallLimit) {
    public EvaluationContext {
        if (scenario == null || result == null || modelCallLimit < 0 || toolCallLimit < 0) {
            throw new IllegalArgumentException("Invalid evaluation context");
        }
    }

    public boolean observableRun() { return result.runId() != null; }
}
