package com.example.agent.workflow;

import java.time.Duration;

/** Bounded aggregate runtime state; it deliberately contains no run identifiers or task contents. */
public record RunQueueSnapshot(long recoverableRuns, long expiredLeases, long recentFailedRuns, Duration oldestWait) {
    public RunQueueSnapshot {
        if (recoverableRuns < 0 || expiredLeases < 0 || recentFailedRuns < 0
                || oldestWait == null || oldestWait.isNegative()) {
            throw new IllegalArgumentException("Invalid Run queue snapshot");
        }
    }
}
