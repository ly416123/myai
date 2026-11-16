package com.example.agent.app;

/**
 * Identifies the caller at the application boundary without claiming that the caller was authenticated.
 *
 * <p>The value is request-scoped, is not persisted with a Run, and must not be used as an authorization
 * credential. External adapters can later populate it from a trusted identity provider.</p>
 */
public record CallerContext(String callerId) {
    public CallerContext {
        if (callerId == null || callerId.isBlank()) {
            throw new IllegalArgumentException("Caller ID must not be blank");
        }
        callerId = callerId.trim();
    }

    /** Returns the explicit non-authenticated identity used by the local command-line adapter. */
    public static CallerContext localCli() {
        return new CallerContext("local-cli");
    }
}
