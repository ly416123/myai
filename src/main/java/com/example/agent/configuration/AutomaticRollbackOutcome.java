package com.example.agent.configuration;

/** Deterministic result of one bounded automatic rollback evaluation. */
public enum AutomaticRollbackOutcome {
    DISABLED, NO_CANDIDATE, COOLDOWN, INSUFFICIENT_SAMPLES, SAMPLE_SCAN_LIMIT,
    THRESHOLD_NOT_MET, ROLLED_BACK, ASSIGNMENT_CHANGED
}
