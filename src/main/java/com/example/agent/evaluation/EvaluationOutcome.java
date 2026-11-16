package com.example.agent.evaluation;

/** Explicit evaluator decision; UNKNOWN is never treated as a passing result. */
public enum EvaluationOutcome {
    PASS,
    FAIL,
    UNKNOWN,
    NOT_APPLICABLE
}
