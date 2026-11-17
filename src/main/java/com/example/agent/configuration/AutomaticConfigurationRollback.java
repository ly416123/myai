package com.example.agent.configuration;

import com.example.agent.workflow.RunRecord;
import com.example.agent.workflow.RunStatus;
import com.example.agent.workflow.RunStore;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Evaluates a single configured environment/agent. It never decides from missing or truncated data and
 * only rolls back the candidate side of an active gray assignment, leaving the stable release in place.
 */
public final class AutomaticConfigurationRollback {
    private static final String SYSTEM_ACTOR = "system-auto-rollback";
    private final AgentConfigurationService configurationService;
    private final RunStore runStore;
    private final AutomaticRollbackPolicy policy;
    private final Clock clock;

    public AutomaticConfigurationRollback(AgentConfigurationService configurationService, RunStore runStore,
                                          AutomaticRollbackPolicy policy, Clock clock) {
        this.configurationService = Objects.requireNonNull(configurationService);
        this.runStore = Objects.requireNonNull(runStore);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
    }

    public AutomaticRollbackResult evaluate(String environment, String agentId) {
        if (!policy.enabled()) return result(AutomaticRollbackOutcome.DISABLED, 0, 0, null);
        Instant now = clock.instant();
        ActiveConfigAssignment assignment = configurationService.current(environment, agentId);
        if (assignment == null || assignment.candidateReleaseId() == null)
            return result(AutomaticRollbackOutcome.NO_CANDIDATE, 0, 0, null);
        if (inCooldown(environment, agentId, now))
            return result(AutomaticRollbackOutcome.COOLDOWN, 0, 0, null);

        List<RunRecord> recent = runStore.listRecent(policy.scanLimit());
        // A full page means the time-window sample may be truncated. Fail closed rather than deciding
        // from a potentially biased subset; a future indexed store query can remove this conservative limit.
        if (recent.size() >= policy.scanLimit())
            return result(AutomaticRollbackOutcome.SAMPLE_SCAN_LIMIT, 0, 0, null);

        Instant windowStart = now.minus(policy.observationWindow());
        if (assignment.updatedAt().isAfter(windowStart)) windowStart = assignment.updatedAt();
        int samples = 0;
        int failures = 0;
        for (RunRecord run : recent) {
            if (run.configurationSnapshot() == null
                    || !assignment.candidateReleaseId().equals(run.configurationSnapshot().releaseId())
                    || run.updatedAt() == null || run.updatedAt().isBefore(windowStart) || run.updatedAt().isAfter(now)) {
                continue;
            }
            if (run.status() == RunStatus.SUCCEEDED || run.status() == RunStatus.PARTIAL
                    || run.status() == RunStatus.FAILED) {
                samples++;
                if (run.status() == RunStatus.PARTIAL || run.status() == RunStatus.FAILED) failures++;
            }
        }
        if (samples < policy.minimumSamples())
            return result(AutomaticRollbackOutcome.INSUFFICIENT_SAMPLES, samples, failures,
                    samples == 0 ? null : (double) failures / samples);
        double failureRate = (double) failures / samples;
        if (failureRate < policy.failureRateThreshold())
            return result(AutomaticRollbackOutcome.THRESHOLD_NOT_MET, samples, failures, failureRate);

        String reason = "Candidate " + assignment.candidateReleaseId() + " exceeded failure rate "
                + Math.round(failureRate * 10000.0) / 100.0 + "% with " + failures + "/" + samples
                + " completed Runs in the configured observation window";
        boolean changed = configurationService.automaticRollback(environment, agentId, assignment,
                SYSTEM_ACTOR, reason, now);
        return result(changed ? AutomaticRollbackOutcome.ROLLED_BACK : AutomaticRollbackOutcome.ASSIGNMENT_CHANGED,
                samples, failures, failureRate);
    }

    private boolean inCooldown(String environment, String agentId, Instant now) {
        if (policy.cooldown().isZero()) return false;
        return configurationService.history(environment, agentId, 500).stream()
                .filter(event -> event.action() == ReleaseAction.AUTO_ROLLBACK)
                .anyMatch(event -> event.occurredAt().plus(policy.cooldown()).isAfter(now));
    }

    private static AutomaticRollbackResult result(AutomaticRollbackOutcome outcome, int samples, int failures,
                                                  Double rate) {
        return new AutomaticRollbackResult(outcome, samples, failures, rate);
    }
}
