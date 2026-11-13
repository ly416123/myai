package com.example.agent.workflow;

/** Signals a temporary persistence outage that may clear while a Run still owns its lease. */
public final class RunStoreUnavailableException extends RuntimeException {
    public RunStoreUnavailableException(Throwable cause) {
        super("Run store temporarily unavailable", cause);
    }
}
