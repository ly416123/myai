package com.example.agent.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import com.example.agent.observability.AgentTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunPollingWorkerTest {
    @TempDir
    Path tempDirectory;

    @Test
    void countsDatabasePollingFailuresWithoutKillingWorkerProcess() throws Exception {
        CountDownLatch polled = new CountDownLatch(1);
        RunStore unavailable = mock(RunStore.class);
        when(unavailable.queueSnapshot(any())).thenAnswer(invocation -> {
            polled.countDown();
            throw new RunStoreUnavailableException(new IllegalStateException("sensitive connection detail"));
        });
        var meters = new SimpleMeterRegistry();
        var telemetry = new AgentTelemetry(meters, ObservationRegistry.create());
        Path ready = tempDirectory.resolve("worker-ready");
        Path live = tempDirectory.resolve("worker-live");

        try (RunPollingWorker worker = new RunPollingWorker(unavailable, run -> run, 1, Duration.ofSeconds(10),
                telemetry, new WorkerHealthFile(ready), new WorkerHealthFile(live))) {
            worker.start();

            assertTrue(polled.await(2, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (meters.find("agent.worker.poll.failures").counter() == null
                    && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(1.0, meters.get("agent.worker.poll.failures").counter().count());
            assertFalse(new WorkerHealthFile(ready).isFresh(Duration.ofSeconds(15)));
            assertTrue(new WorkerHealthFile(live).isFresh(Duration.ofSeconds(15)));
            assertTrue(meters.get("agent.worker.poll.failures").counter().getId().getTags().isEmpty());
        }
    }

    @Test
    void pollRefreshesBacklogAndExpiredLeaseGaugesFromStoreSnapshot() throws Exception {
        InMemoryRunStore store = new InMemoryRunStore();
        RunRecord queued = RunRecord.create("metrics-queued-run", "sensitive task", Duration.ofMinutes(1));
        store.save(queued);
        var meters = new SimpleMeterRegistry();
        var telemetry = new AgentTelemetry(meters, ObservationRegistry.create());

        Path marker = tempDirectory.resolve("worker-ready");
        try (RunPollingWorker worker = new RunPollingWorker(store, run -> run, 1, Duration.ofSeconds(60), telemetry,
                new WorkerHealthFile(marker))) {
            worker.pollOnce();

            assertEquals(1.0, meters.get("agent.worker.queue.depth").gauge().value());
            assertEquals(0.0, meters.get("agent.worker.lease.expired").gauge().value());
            assertTrue(meters.get("agent.worker.queue.oldest").gauge().value() >= 0.0);
            assertTrue(new WorkerHealthFile(marker).isFresh(Duration.ofSeconds(15)));
        }
    }

    @Test
    void rejectsUnboundedOrSubMillisecondPollingConfiguration() {
        InMemoryRunStore store = new InMemoryRunStore();
        assertThrows(IllegalArgumentException.class,
                () -> new RunPollingWorker(store, run -> null, 257, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new RunPollingWorker(store, run -> null, 1, Duration.ofNanos(1)));
    }

    @Test
    void dispatchesBoundedRecoverableRunsAndPreservesWorkflowType() throws Exception {
        InMemoryRunStore store = new InMemoryRunStore();
        RunRecord single = RunRecord.create("a-single-pending", "first", Duration.ofMinutes(1));
        RunRecord multi = RunRecord.create("b-multi-pending", "second", Duration.ofMinutes(1), RunType.COLLABORATIVE);
        store.save(single);
        store.save(multi);
        BlockingQueue<RunRecord> dispatched = new LinkedBlockingQueue<>();
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try (RunPollingWorker worker = new RunPollingWorker(store, run -> {
            Instant now = Instant.now();
            LeaseToken lease = store.claim(run.runId(), "test-worker-" + run.runId(), now,
                    Duration.ofMinutes(1)).orElseThrow();
            RunRecord current = store.find(run.runId()).orElseThrow();
            if (current.status() == RunStatus.CREATED) current.transitionTo(RunStatus.RUNNING, now, null);
            assertTrue(store.saveFenced(current, lease, now));
            dispatched.add(current);
            if (run.runId().equals("a-single-pending")) {
                try {
                    releaseFirst.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            RunRecord completed = store.find(run.runId()).orElseThrow();
            completed.transitionTo(RunStatus.SUCCEEDED, Instant.now(), null);
            assertTrue(store.saveFenced(completed, lease, Instant.now()));
            return store.find(run.runId()).orElseThrow();
        }, 1, Duration.ofMillis(25))) {
            worker.pollOnce();
            RunRecord first = dispatched.poll(2, TimeUnit.SECONDS);
            assertNotNull(first);
            assertEquals("a-single-pending", first.runId());
            assertEquals(RunType.SINGLE_AGENT, first.runType());

            worker.pollOnce();
            assertNull(dispatched.poll(100, TimeUnit.MILLISECONDS), "worker must respect its concurrency bound");

            releaseFirst.countDown();
            RunRecord second = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (second == null && System.nanoTime() < deadline) {
                worker.pollOnce();
                second = dispatched.poll(20, TimeUnit.MILLISECONDS);
            }
            assertNotNull(second);
            assertEquals("b-multi-pending", second.runId());
            assertEquals(RunType.COLLABORATIVE, second.runType());
        } finally {
            releaseFirst.countDown();
        }
    }
}
