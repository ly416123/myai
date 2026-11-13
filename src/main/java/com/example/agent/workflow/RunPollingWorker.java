package com.example.agent.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.agent.observability.AgentTelemetry;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Polls PostgreSQL for abandoned or newly submitted Runs and dispatches them to the existing workflow runtime. */
public final class RunPollingWorker implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RunPollingWorker.class);
    private final RunStore store;
    private final RunRecoveryHandler handler;
    private final int concurrency;
    private final Duration pollInterval;
    private final AgentTelemetry telemetry;
    private final WorkerHealthFile healthFile;
    private final WorkerHealthFile livenessFile;
    private final ConcurrentHashMap<String, RunRecord> activeRuns = new ConcurrentHashMap<>();
    private final ExecutorService workers;
    private final ScheduledExecutorService poller;

    public RunPollingWorker(RunStore store, RunRecoveryHandler handler, int concurrency, Duration pollInterval) {
        this(store, handler, concurrency, pollInterval, AgentTelemetry.noop());
    }

    public RunPollingWorker(RunStore store, RunRecoveryHandler handler, int concurrency, Duration pollInterval,
                            AgentTelemetry telemetry) {
        this(store, handler, concurrency, pollInterval, telemetry,
                new WorkerHealthFile(WorkerHealthFile.DEFAULT_PATH),
                new WorkerHealthFile(WorkerHealthFile.DEFAULT_LIVENESS_PATH));
    }

    RunPollingWorker(RunStore store, RunRecoveryHandler handler, int concurrency, Duration pollInterval,
                     AgentTelemetry telemetry, WorkerHealthFile healthFile) {
        this(store, handler, concurrency, pollInterval, telemetry, healthFile,
                new WorkerHealthFile(WorkerHealthFile.DEFAULT_LIVENESS_PATH));
    }

    RunPollingWorker(RunStore store, RunRecoveryHandler handler, int concurrency, Duration pollInterval,
                     AgentTelemetry telemetry, WorkerHealthFile healthFile, WorkerHealthFile livenessFile) {
        if (store == null || handler == null || concurrency < 1 || concurrency > 256 || pollInterval == null
                || pollInterval.toMillis() < 1 || telemetry == null || healthFile == null || livenessFile == null) {
            throw new IllegalArgumentException("Invalid Run polling worker configuration");
        }
        this.store = store;
        this.handler = handler;
        this.concurrency = concurrency;
        this.pollInterval = pollInterval;
        this.telemetry = telemetry;
        this.healthFile = healthFile;
        this.livenessFile = livenessFile;
        this.workers = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "agent-run-worker");
            thread.setDaemon(false);
            return thread;
        });
        this.poller = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-run-poller");
            thread.setDaemon(false);
            return thread;
        });
    }

    public void start() {
        poller.scheduleWithFixedDelay(this::pollSafely, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("runWorker=status_started concurrency={} pollIntervalMs={}", concurrency, pollInterval.toMillis());
    }

    /** One bounded scan, also exposed for deterministic lifecycle checks. */
    public void pollOnce() {
        Instant now = Instant.now();
        telemetry.recordQueueSnapshot(store.queueSnapshot(now));
        healthFile.recordHeartbeat();
        int freeSlots = concurrency - activeRuns.size();
        if (freeSlots <= 0) return;
        for (RunRecord candidate : store.findRecoverable(now, freeSlots)) {
            if (activeRuns.putIfAbsent(candidate.runId(), candidate) != null) continue;
            try {
                workers.execute(() -> process(candidate));
            } catch (RuntimeException rejected) {
                activeRuns.remove(candidate.runId(), candidate);
                throw rejected;
            }
        }
    }

    void pollSafely() {
        try {
            livenessFile.recordHeartbeat();
            pollOnce();
            telemetry.recordRunWorkerPollHeartbeat();
        } catch (RuntimeException failure) {
            telemetry.recordWorkerPollFailure();
            log.warn("runWorker=poll_failed exceptionType={}", failure.getClass().getSimpleName());
        }
    }

    private void process(RunRecord run) {
        Instant dispatchAt = Instant.now();
        Duration queueWait = null;
        if (run.status() == RunStatus.CREATED) {
            queueWait = Duration.between(run.createdAt(), dispatchAt);
        } else if (run.leaseExpiresAt() != null) {
            queueWait = Duration.between(run.leaseExpiresAt(), dispatchAt);
        }
        if (queueWait != null && queueWait.isNegative()) queueWait = Duration.ZERO;
        try {
            RunRecord outcome = handler.resume(run);
            telemetry.recordWorkerDispatch(run.runType(), queueWait);
            log.info("runId={} runWorker=completed type={} status={} queueWaitMs={}", run.runId(), run.runType(),
                    outcome.status(), queueWait == null ? "unknown" : queueWait.toMillis());
        } catch (RuntimeException failure) {
            // Another instance may win the lease after the scan. Keep the log free of prompts and provider errors.
            log.info("runId={} runWorker=dispatch_finished exceptionType={}",
                    run.runId(), failure.getClass().getSimpleName());
        } finally {
            activeRuns.remove(run.runId(), run);
        }
    }

    @Override
    public void close() {
        poller.shutdownNow();
        for (RunRecord run : activeRuns.values()) {
            try {
                handler.cancel(run.runId(), run.runType());
            } catch (RuntimeException failure) {
                log.warn("runId={} runWorker=cancel_rejected exceptionType={}",
                        run.runId(), failure.getClass().getSimpleName());
            }
        }
        workers.shutdown();
        try {
            if (!workers.awaitTermination(20, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    public interface RunRecoveryHandler {
        RunRecord resume(RunRecord run);
        default void cancel(String runId, RunType type) { }
    }
}
