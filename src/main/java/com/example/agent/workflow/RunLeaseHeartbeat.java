package com.example.agent.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Renews a parent Run lease while workflow code is blocked on model or tool work. */
public final class RunLeaseHeartbeat implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RunLeaseHeartbeat.class);

    private final RunStore store;
    private final LeaseToken lease;
    private final Duration leaseDuration;
    private final Runnable onLeaseLost;
    private final AtomicBoolean lost = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledThreadPoolExecutor scheduler;
    private final Object expiryLock = new Object();
    private volatile long validUntilNanos;
    private ScheduledFuture<?> expiryWatchdog;

    public RunLeaseHeartbeat(RunStore store, LeaseToken lease, RunLeasePolicy policy, Runnable onLeaseLost) {
        this.store = Objects.requireNonNull(store);
        this.lease = Objects.requireNonNull(lease);
        Objects.requireNonNull(policy);
        this.leaseDuration = policy.duration();
        this.onLeaseLost = Objects.requireNonNull(onLeaseLost);
        this.validUntilNanos = System.nanoTime() + leaseDuration.toNanos();
        this.scheduler = new ScheduledThreadPoolExecutor(2, task -> {
            Thread thread = new Thread(task, "agent-run-lease-heartbeat-" + lease.runId());
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.setRemoveOnCancelPolicy(true);
        synchronized (expiryLock) {
            scheduleExpiryWatchdogLocked();
        }
        scheduler.scheduleWithFixedDelay(this::renewSafely,
                policy.renewalInterval().toMillis(), policy.renewalInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    public boolean isLost() {
        return lost.get();
    }

    private void renewSafely() {
        if (closed.get() || lost.get()) return;
        Instant now = Instant.now();
        long renewalStartedAt = System.nanoTime();
        try {
            if (!store.renew(lease, now, leaseDuration)) {
                markLost();
                return;
            }
            long renewedUntilNanos = renewalStartedAt + leaseDuration.toNanos();
            if (System.nanoTime() >= renewedUntilNanos) {
                markLost();
                return;
            }
            synchronized (expiryLock) {
                if (closed.get() || lost.get()) return;
                validUntilNanos = renewedUntilNanos;
                scheduleExpiryWatchdogLocked();
            }
        } catch (RuntimeException unavailable) {
            // A transient store error is not itself proof that fencing changed. Keep retrying,
            // but stop before the last confirmed lease expiry so this process cannot act stale.
            log.warn("runId={} leaseHeartbeat=renew_failed exceptionType={}", lease.runId(),
                    unavailable.getClass().getSimpleName());
            if (System.nanoTime() >= validUntilNanos) markLost();
        }
    }

    private void scheduleExpiryWatchdogLocked() {
        if (expiryWatchdog != null) expiryWatchdog.cancel(false);
        long remainingNanos = validUntilNanos - System.nanoTime();
        expiryWatchdog = scheduler.schedule(this::checkLocalExpiry,
                Math.max(0, remainingNanos), TimeUnit.NANOSECONDS);
    }

    private void checkLocalExpiry() {
        boolean notify = false;
        synchronized (expiryLock) {
            if (closed.get() || lost.get()) return;
            if (System.nanoTime() >= validUntilNanos) {
                notify = lost.compareAndSet(false, true);
            } else {
                // A renewal may have raced with the watchdog's scheduled deadline.
                scheduleExpiryWatchdogLocked();
            }
        }
        if (notify) notifyLeaseLost();
    }

    private void markLost() {
        if (lost.compareAndSet(false, true)) {
            notifyLeaseLost();
        }
    }

    private void notifyLeaseLost() {
        log.warn("runId={} leaseHeartbeat=lost fencingToken={}", lease.runId(), lease.fencingToken());
        onLeaseLost.run();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (expiryLock) {
            if (expiryWatchdog != null) expiryWatchdog.cancel(false);
        }
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
