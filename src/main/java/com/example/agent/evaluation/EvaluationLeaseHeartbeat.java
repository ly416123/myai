package com.example.agent.evaluation;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps a single evaluation owner alive; process death stops renewal and enables fenced recovery. */
final class EvaluationLeaseHeartbeat implements AutoCloseable {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "evaluation-lease-heartbeat"); thread.setDaemon(true); return thread;
    });
    private final AtomicBoolean valid = new AtomicBoolean(true);
    EvaluationLeaseHeartbeat(EvaluationRepository repository, EvaluationLease lease) {
        scheduler.scheduleAtFixedRate(() -> {
            Instant now = Instant.now();
            try {
                if (!repository.renew(lease, now, now.plusSeconds(45))) valid.set(false);
            } catch (RuntimeException failure) {
                valid.set(false);
            }
        }, 10, 10, TimeUnit.SECONDS);
    }
    void assertValid() { if (!valid.get()) throw new IllegalStateException("Evaluation execution lease was lost"); }
    @Override public void close() { scheduler.shutdownNow(); }
}
