package com.example.agent.configuration;

import java.time.Duration;

/** Explicit, bounded policy for reverting a gray candidate based on persisted Run outcomes. */
public record AutomaticRollbackPolicy(boolean enabled, int minimumSamples, double failureRateThreshold,
                                      Duration observationWindow, Duration cooldown, int scanLimit) {
    public AutomaticRollbackPolicy {
        if (minimumSamples < 1 || minimumSamples > 10_000 || !Double.isFinite(failureRateThreshold)
                || failureRateThreshold <= 0 || failureRateThreshold > 1
                || observationWindow == null || observationWindow.isZero() || observationWindow.isNegative()
                || cooldown == null || cooldown.isNegative() || scanLimit < minimumSamples || scanLimit > 10_000) {
            throw new IllegalArgumentException("Automatic rollback policy is invalid");
        }
    }

    public static AutomaticRollbackPolicy disabled() {
        return new AutomaticRollbackPolicy(false, 10, 0.5, Duration.ofMinutes(15),
                Duration.ofMinutes(30), 1000);
    }
}
