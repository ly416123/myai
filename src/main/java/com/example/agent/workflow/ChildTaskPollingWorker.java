package com.example.agent.workflow;

import com.example.agent.collaboration.CollaborativeWorkflow;
import com.example.agent.collaboration.TaskStatus;
import com.example.agent.collaboration.WorkerRegistry;
import com.example.agent.collaboration.WorkerSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Claims durable collaborative tasks from PostgreSQL while their parent Supervisor is active. */
public final class ChildTaskPollingWorker implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ChildTaskPollingWorker.class);
    private final RunStore store;
    private final CollaborativeWorkflow workflow;
    private final WorkerRegistry workers;
    private final int concurrency;
    private final Duration pollInterval;
    private final String ownerId = "task-worker-" + UUID.randomUUID();
    private final Set<String> activeTasks = ConcurrentHashMap.newKeySet();
    private final Map<String, ActiveTaskLease> activeLeases = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final ScheduledExecutorService poller;

    public ChildTaskPollingWorker(RunStore store, CollaborativeWorkflow workflow, WorkerRegistry workers,
                                 int concurrency, Duration pollInterval) {
        if (store == null || workflow == null || workers == null || concurrency < 1 || concurrency > 256
                || pollInterval == null || pollInterval.toMillis() < 1) {
            throw new IllegalArgumentException("Invalid child task worker configuration");
        }
        this.store = store;
        this.workflow = workflow;
        this.workers = workers;
        this.concurrency = concurrency;
        this.pollInterval = pollInterval;
        this.executor = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "agent-child-task-worker");
            thread.setDaemon(false);
            return thread;
        });
        this.poller = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-child-task-poller");
            thread.setDaemon(false);
            return thread;
        });
    }

    public void start() {
        poller.scheduleWithFixedDelay(this::pollSafely, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("childTaskWorker=status_started concurrency={} pollIntervalMs={}", concurrency,
                pollInterval.toMillis());
    }

    /** Bounded scan used by the scheduled loop and deterministic tests. */
    public void pollOnce() {
        renewActiveLeases(Instant.now());
        int freeSlots = concurrency - activeTasks.size();
        if (freeSlots <= 0) return;
        Instant now = Instant.now();
        for (RunRecord run : store.findActiveCollaborativeRuns(now, Math.max(16, freeSlots * 4))) {
            if (freeSlots <= 0) return;
            LeaseToken parentLease = new LeaseToken(run.runId(), run.leaseOwner(), run.fencingToken());
            for (var task : run.childTasks()) {
                if (freeSlots <= 0) return;
                if (task.status() != TaskStatus.PENDING && task.status() != TaskStatus.IN_PROGRESS) continue;
                if (!task.dependencies().stream().allMatch(dependency -> run.childTasks().stream()
                        .filter(candidate -> candidate.taskId().equals(dependency))
                        .anyMatch(candidate -> candidate.status() == TaskStatus.SUCCEEDED))) continue;
                WorkerSpec worker = workers.find(task.workerId()).orElse(null);
                if (worker == null) {
                    log.warn("runId={} taskId={} childTaskWorker=worker_not_registered", run.runId(), task.taskId());
                    continue;
                }
                String key = run.runId() + '\u0000' + task.taskId();
                if (!activeTasks.add(key)) continue;
                Duration leaseDuration = taskLeaseDuration();
                TaskLeaseToken taskLease = store.claimChildTask(parentLease, task.taskId(), ownerId, now,
                        leaseDuration).orElse(null);
                if (taskLease == null) {
                    activeTasks.remove(key);
                    continue;
                }
                activeLeases.put(key, new ActiveTaskLease(parentLease, taskLease));
                freeSlots--;
                try {
                    executor.execute(() -> execute(run, parentLease, taskLease, key));
                } catch (RuntimeException rejected) {
                    activeTasks.remove(key);
                    activeLeases.remove(key);
                    log.warn("runId={} taskId={} childTaskWorker=dispatch_rejected exceptionType={}",
                            run.runId(), task.taskId(), rejected.getClass().getSimpleName());
                    return;
                }
            }
        }
    }

    private void execute(RunRecord run, LeaseToken parentLease, TaskLeaseToken taskLease, String key) {
        try {
            workflow.executeClaimedTask(run, parentLease, taskLease);
        } catch (RuntimeException failure) {
            // Do not include model output, tool arguments, or exception messages in worker logs.
            log.warn("runId={} taskId={} childTaskWorker=execution_finished exceptionType={}",
                    run.runId(), taskLease.taskId(), failure.getClass().getSimpleName());
        } finally {
            activeTasks.remove(key);
            activeLeases.remove(key);
        }
    }

    private void renewActiveLeases(Instant now) {
        Duration duration = taskLeaseDuration();
        for (var entry : activeLeases.entrySet()) {
            ActiveTaskLease lease = entry.getValue();
            if (!store.renewChildTaskLease(lease.parentLease(), lease.taskLease(), now, duration)) {
                if (activeLeases.remove(entry.getKey(), lease)) {
                    log.warn("runId={} taskId={} childTaskWorker=lease_lost", lease.taskLease().runId(),
                            lease.taskLease().taskId());
                }
            }
        }
    }

    private Duration taskLeaseDuration() {
        Duration pollingMargin = pollInterval.multipliedBy(3);
        return pollingMargin.compareTo(Duration.ofSeconds(30)) < 0 ? Duration.ofSeconds(30) : pollingMargin;
    }

    private void pollSafely() {
        try {
            pollOnce();
        } catch (RuntimeException failure) {
            log.warn("childTaskWorker=poll_failed exceptionType={}", failure.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        poller.shutdownNow();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(20, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private record ActiveTaskLease(LeaseToken parentLease, TaskLeaseToken taskLease) { }
}
