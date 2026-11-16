package com.example.agent.evaluation;

import java.util.List;
import java.util.Set;

/** Stable identifiers for deterministic evaluators supported by the runtime. */
public final class EvaluationEvaluatorIds {
    public static final String RUN_OUTCOME = "run-outcome";
    public static final String TOOL_SELECTION = "tool-selection";
    public static final String COLLABORATION_PLAN = "collaboration-plan";
    public static final String ANSWER_ASSERTIONS = "answer-assertions";
    public static final String CALL_BUDGET = "call-budget";
    public static final String STRUCTURED_OUTPUT = "structured-output";
    public static final String EXPECTED_FAILURE_CATEGORY = "expected-failure-category";

    private static final List<String> DEFAULTS = List.of(RUN_OUTCOME, TOOL_SELECTION,
            COLLABORATION_PLAN, ANSWER_ASSERTIONS, CALL_BUDGET, STRUCTURED_OUTPUT, EXPECTED_FAILURE_CATEGORY);
    private static final Set<String> SUPPORTED = Set.copyOf(DEFAULTS);

    private EvaluationEvaluatorIds() { }

    public static List<String> defaults() { return DEFAULTS; }
    public static boolean isSupported(String id) { return SUPPORTED.contains(id); }
}
