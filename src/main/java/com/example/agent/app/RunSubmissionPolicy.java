package com.example.agent.app;

import com.example.agent.workflow.RunType;

/** Application boundary for validating who may submit a kind of Run. */
@FunctionalInterface
public interface RunSubmissionPolicy {
    void validate(CallerContext caller, RunType runType);

    /** Explicitly disables authentication and authorization while the only entry point is the local CLI. */
    static RunSubmissionPolicy noAuthentication() {
        return (caller, runType) -> { };
    }
}
