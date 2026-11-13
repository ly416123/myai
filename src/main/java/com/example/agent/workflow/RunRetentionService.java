package com.example.agent.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Bounded retention sweeper for terminal Runs; a zero retention period disables deletion. */
public final class RunRetentionService {
    private static final Logger log = LoggerFactory.getLogger(RunRetentionService.class);
    private static final int MAX_BATCHES_PER_SWEEP = 10;
    private final RunStore store;
    private final Duration retentionPeriod;
    private final int batchSize;
    private final Clock clock;

    public RunRetentionService(RunStore store, Duration retentionPeriod, int batchSize) {
        this(store, retentionPeriod, batchSize, Clock.systemUTC());
    }

    RunRetentionService(RunStore store, Duration retentionPeriod, int batchSize, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.retentionPeriod = Objects.requireNonNull(retentionPeriod);
        this.clock = Objects.requireNonNull(clock);
        if (retentionPeriod.isNegative() || batchSize < 1 || batchSize > 10_000)
            throw new IllegalArgumentException("Invalid Run retention policy");
        this.batchSize = batchSize;
    }

    /** Deletes at most ten bounded batches; each batch commits independently. */
    public int runOnce() {
        if (retentionPeriod.isZero()) return 0;
        Instant cutoff = clock.instant().minus(retentionPeriod);
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_SWEEP; batch++) {
            int deleted = store.purgeTerminalRunsBefore(cutoff, batchSize);
            total += deleted;
            if (deleted < batchSize) break;
        }
        log.info("runRetention completed deletedRuns={} cutoff={}", total, cutoff);
        return total;
    }

    @Scheduled(fixedDelayString = "${agent.retention.interval:PT24H}",
            initialDelayString = "${agent.retention.initial-delay:PT1M}")
    public void scheduledCleanup() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.error("runRetention failed failureType={}", failure.getClass().getSimpleName());
        }
    }
}
