package com.example.agent.workflow;

import java.time.Duration;

/** Parent Run lease duration and heartbeat cadence shared by both workflow types. */
public record RunLeasePolicy(Duration duration, Duration renewalInterval) {
    public static final RunLeasePolicy DEFAULT = new RunLeasePolicy(Duration.ofSeconds(30), Duration.ofSeconds(10));

    public RunLeasePolicy {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Run lease duration must be positive");
        }
        if (renewalInterval == null || renewalInterval.isZero() || renewalInterval.isNegative()
                || renewalInterval.multipliedBy(2).compareTo(duration) >= 0) {
            throw new IllegalArgumentException("Run lease renewal interval must be less than half the lease duration");
        }
    }
}
