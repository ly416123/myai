package com.example.agent.evaluation;

public interface EvaluationProgressListener {
    EvaluationProgressListener NOOP = new EvaluationProgressListener() { };
    default void caseStarted(AgentEvaluationScenario scenario) { }
    default void caseCompleted(AgentEvaluationScenario scenario, AgentEvaluationCaseResult result) { }
}
