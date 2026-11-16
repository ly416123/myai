package com.example.agent.evaluation;

/** Pure deterministic scoring rule. Implementations must not call models or perform I/O. */
public interface EvaluationEvaluator {
    String id();
    String version();
    EvaluationScore evaluate(EvaluationContext context);
}
