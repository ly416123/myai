package com.example.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.agent.collaboration.ChildTaskRecord;
import com.example.agent.collaboration.PlannedTask;
import com.example.agent.collaboration.TaskPlan;
import com.example.agent.collaboration.TaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RunStoreLeaseTest {
    private final InMemoryRunStore store = new InMemoryRunStore();

    @Test
    void recentRunListingIsNewestFirstAndBounded() {
        Instant base = Instant.parse("2026-11-12T00:00:00Z");
        for (int i = 1; i <= 4; i++) {
            RunRecord run = RunRecord.create("recent-" + i, "task", Duration.ofMinutes(1));
            run.setCreatedAt(base.plusSeconds(i));
            store.save(run);
        }

        assertEquals(List.of("recent-4", "recent-3"), store.listRecent(2).stream()
                .map(RunRecord::runId).toList());
        assertThrows(IllegalArgumentException.class, () -> store.listRecent(0));
    }

    @Test
    void fencedParentCheckpointRetainsNewlyPlannedChildTasks() {
        Instant now = Instant.now();
        RunRecord run = RunRecord.create("new-child-projection", "delegate", Duration.ofMinutes(2), RunType.COLLABORATIVE);
        store.save(run);
        LeaseToken lease = store.claim(run.runId(), "supervisor", now, Duration.ofMinutes(1)).orElseThrow();
        PlannedTask planned = new PlannedTask("new-task", "worker-a", "work", List.of(), List.of("out"));
        run.setTaskPlan(new TaskPlan(TaskPlan.Route.DELEGATE, List.of(planned), List.of()));
        run.addChildTask(ChildTaskRecord.pending(planned));

        assertTrue(store.saveFenced(run, lease, now.plusMillis(1)));
        assertEquals("new-task", store.find(run.runId()).orElseThrow().childTasks().getFirst().taskId());
    }

    @Test
    void recoverableBatchExcludesLiveLeasesAndTerminalRuns() {
        Instant now = Instant.parse("2026-11-12T00:00:00Z");
        RunRecord unclaimed = RunRecord.create("unclaimed", "task", Duration.ofMinutes(1));
        RunRecord leased = RunRecord.create("leased", "task", Duration.ofMinutes(1));
        RunRecord completed = RunRecord.create("completed", "task", Duration.ofMinutes(1));
        completed.transitionTo(RunStatus.RUNNING, now, null);
        completed.transitionTo(RunStatus.SUCCEEDED, now.plusSeconds(1), null);
        store.save(unclaimed);
        store.save(leased);
        store.save(completed);
        store.claim("leased", "worker", now, Duration.ofSeconds(10));

        assertEquals(List.of("unclaimed"), store.findRecoverable(now.plusSeconds(5), 10).stream()
                .map(RunRecord::runId).toList());
        assertEquals(List.of("unclaimed", "leased"), store.findRecoverable(now.plusSeconds(11), 10).stream()
                .map(RunRecord::runId).toList());
        assertEquals(1, store.findRecoverable(now.plusSeconds(11), 1).size());
    }

    @Test
    void listsOnlyActivelyLeasedCollaborativeRunsWithPendingTasks() {
        Instant now = Instant.now();
        RunRecord eligible = collaborativeRun("active-collaborative");
        eligible.transitionTo(RunStatus.RUNNING, now, null);
        RunRecord inactive = collaborativeRun("inactive-collaborative");
        inactive.transitionTo(RunStatus.RUNNING, now, null);
        RunRecord single = RunRecord.create("active-single", "task", Duration.ofMinutes(1));
        single.transitionTo(RunStatus.RUNNING, now, null);
        store.save(eligible);
        store.save(inactive);
        store.save(single);
        store.claim(eligible.runId(), "supervisor", now, Duration.ofMinutes(1));
        store.claim(inactive.runId(), "supervisor", now.minusSeconds(90), Duration.ofSeconds(30));
        store.claim(single.runId(), "supervisor", now, Duration.ofMinutes(1));

        List<RunRecord> results = store.findActiveCollaborativeRuns(now, 10);

        assertEquals(List.of("active-collaborative"), results.stream().map(RunRecord::runId).toList());
        assertEquals("supervisor", results.getFirst().leaseOwner());
        assertEquals(1, results.getFirst().fencingToken());
    }

    @Test
    void queueSnapshotCountsCreatedRunsAndExpiredRunLeasesOnly() {
        Instant now = Instant.parse("2026-11-12T00:00:00Z");
        RunRecord created = RunRecord.create("snapshot-created", "task", Duration.ofMinutes(1));
        RunRecord expired = RunRecord.create("snapshot-expired", "task", Duration.ofMinutes(1));
        expired.transitionTo(RunStatus.RUNNING, now.minusSeconds(2), null);
        RunRecord active = RunRecord.create("snapshot-active", "task", Duration.ofMinutes(1));
        active.transitionTo(RunStatus.RUNNING, now.minusSeconds(2), null);
        RunRecord completed = RunRecord.create("snapshot-completed", "task", Duration.ofMinutes(1));
        completed.transitionTo(RunStatus.RUNNING, now.minusSeconds(2), null);
        completed.transitionTo(RunStatus.SUCCEEDED, now.minusSeconds(1), null);
        store.save(created);
        store.save(expired);
        store.save(active);
        store.save(completed);
        store.claim("snapshot-expired", "expired-owner", now.minusSeconds(20), Duration.ofSeconds(5));
        store.claim("snapshot-active", "active-owner", now, Duration.ofMinutes(1));

        RunQueueSnapshot snapshot = store.queueSnapshot(now);

        assertEquals(2, snapshot.recoverableRuns());
        assertEquals(1, snapshot.expiredLeases());
        assertTrue(snapshot.oldestWait().compareTo(Duration.ZERO) >= 0);
    }

    @Test
    void queueSnapshotIncludesOnlyFailedRunsUpdatedWithinFiveMinutes() {
        Instant now = Instant.now();
        RunRecord recentFailure = RunRecord.create("recent-failure", "task", Duration.ofMinutes(1));
        recentFailure.transitionTo(RunStatus.FAILED, now.plusMillis(1), FailureCategory.MODEL_NETWORK);
        RunRecord oldFailure = RunRecord.create("old-failure", "task", Duration.ofMinutes(1));
        oldFailure.transitionTo(RunStatus.FAILED, now.plusMillis(2), FailureCategory.MODEL_NETWORK);
        oldFailure.setUpdatedAt(now.minus(Duration.ofMinutes(6)));
        RunRecord success = RunRecord.create("recent-success", "task", Duration.ofMinutes(1));
        success.transitionTo(RunStatus.RUNNING, now.plusMillis(3), null);
        success.transitionTo(RunStatus.SUCCEEDED, now.plusMillis(4), null);
        store.save(recentFailure);
        store.save(oldFailure);
        store.save(success);

        RunQueueSnapshot snapshot = store.queueSnapshot(now.plusSeconds(1));

        assertEquals(1, snapshot.recentFailedRuns());
    }

    @Test
    void onlyOneConcurrentWorkerCanClaimRun() throws Exception {
        RunRecord run = RunRecord.create("claim-race", "task", Duration.ofMinutes(1));
        store.save(run);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var claims = new AtomicInteger();

        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                int worker = i;
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (store.claim("claim-race", "worker-" + worker, now, Duration.ofSeconds(10)).isPresent()) {
                        claims.incrementAndGet();
                    }
                    return null;
                });
            }
            ready.await();
            start.countDown();
        }

        assertEquals(1, claims.get());
    }

    @Test
    void expiredLeaseCanBeClaimedButOldFencingTokenCannotWrite() {
        RunRecord run = RunRecord.create("fencing", "task", Duration.ofMinutes(1));
        store.save(run);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        LeaseToken oldToken = store.claim("fencing", "worker-a", start, Duration.ofSeconds(5)).orElseThrow();
        LeaseToken newToken = store.claim("fencing", "worker-b", start.plusSeconds(6), Duration.ofSeconds(5)).orElseThrow();

        assertTrue(newToken.fencingToken() > oldToken.fencingToken());
        run.setFinalAnswer("stale result");
        assertFalse(store.saveFenced(run, oldToken, start.plusSeconds(7)));
        RunRecord current = store.find("fencing").orElseThrow();
        current.setFinalAnswer("current result");
        assertTrue(store.saveFenced(current, newToken, start.plusSeconds(7)));
        assertEquals("current result", store.find("fencing").orElseThrow().finalAnswer());
    }

    @Test
    void onlyCurrentLeaseOwnerCanRenewLease() {
        RunRecord run = RunRecord.create("renew", "task", Duration.ofMinutes(1));
        store.save(run);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        LeaseToken token = store.claim("renew", "worker-a", now, Duration.ofSeconds(5)).orElseThrow();
        LeaseToken wrongOwner = new LeaseToken("renew", "worker-b", token.fencingToken());

        assertFalse(store.renew(wrongOwner, now.plusSeconds(1), Duration.ofSeconds(5)));
        assertTrue(store.renew(token, now.plusSeconds(1), Duration.ofSeconds(10)));
        assertTrue(store.claim("renew", "worker-b", now.plusSeconds(8), Duration.ofSeconds(5)).isEmpty());
    }

    @Test
    void cancellationRequestIsVisibleToCurrentLeaseHolderWithoutInvalidatingItsFence() {
        RunRecord run = RunRecord.create("cancel-request", "task", Duration.ofMinutes(1));
        store.save(run);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        LeaseToken token = store.claim("cancel-request", "worker-a", now, Duration.ofSeconds(30)).orElseThrow();

        assertTrue(store.requestCancellation("cancel-request", now.plusSeconds(1)));
        assertTrue(store.find("cancel-request").orElseThrow().cancellationRequested());
        assertTrue(store.saveFenced(store.find("cancel-request").orElseThrow(), token, now.plusSeconds(2)));
    }

    @Test
    void childCheckpointAndWorkerStepsSurviveStaleParentWrite() {
        RunRecord run = collaborativeRun("parent-child-checkpoint");
        store.save(run);
        Instant now = Instant.now();
        LeaseToken parent = store.claim(run.runId(), "supervisor", now, Duration.ofMinutes(1)).orElseThrow();
        RunRecord staleParent = store.find(run.runId()).orElseThrow();
        TaskLeaseToken child = store.claimChildTask(parent, "task-a", "child-worker", now,
                Duration.ofSeconds(30)).orElseThrow();
        RunStep workerStep = RunStep.workflow("child-step", StepKind.WORKER,
                "task-a", "worker-a", "WORKER", now);
        ChildTaskRecord checkpoint = staleParent.childTasks().getFirst()
                .start(List.of(com.example.agent.model.Message.user("child-local")), now)
                .withSteps(List.of(workerStep));

        assertTrue(store.saveChildTaskFenced(parent, child, checkpoint, now));
        staleParent.addMessage(com.example.agent.model.Message.assistant("supervisor progress"));
        assertTrue(store.saveFenced(staleParent, parent, now.plusMillis(1)));

        RunRecord reloaded = store.find(run.runId()).orElseThrow();
        assertEquals("child-local", reloaded.childTasks().getFirst().messages().getFirst().content());
        assertEquals(List.of("child-step"), reloaded.steps().stream().map(RunStep::stepId).toList());
    }

    @Test
    void childTaskCanBeClaimedOnceAndReclaimedAfterLeaseExpiryWithNewFence() {
        RunRecord run = collaborativeRun("parent-child-lease");
        store.save(run);
        Instant now = Instant.parse("2026-11-12T00:00:00Z");
        LeaseToken parentLease = store.claim(run.runId(), "supervisor-a", now, Duration.ofMinutes(2)).orElseThrow();

        TaskLeaseToken first = store.claimChildTask(parentLease, "task-a", "worker-instance-a", now,
                Duration.ofSeconds(10)).orElseThrow();
        assertTrue(store.claimChildTask(parentLease, "task-a", "worker-instance-b", now.plusSeconds(1),
                Duration.ofSeconds(10)).isEmpty());

        TaskLeaseToken reclaimed = store.claimChildTask(parentLease, "task-a", "worker-instance-b",
                now.plusSeconds(11), Duration.ofSeconds(10)).orElseThrow();
        assertTrue(reclaimed.fencingToken() > first.fencingToken());
        assertEquals("worker-instance-b", reclaimed.ownerId());
        assertFalse(store.isChildTaskLeaseCurrent(first, parentLease, now.plusSeconds(12)));
        assertTrue(store.isChildTaskLeaseCurrent(reclaimed, parentLease, now.plusSeconds(12)));
    }

    @Test
    void supervisorCanSettleAChildLeaseAfterParentRunBecomesTerminal() {
        RunRecord run = collaborativeRun("settle-terminal-child");
        run.transitionTo(RunStatus.RUNNING, Instant.now(), null);
        store.save(run);
        Instant now = Instant.now();
        LeaseToken parent = store.claim(run.runId(), "supervisor", now, Duration.ofMinutes(1)).orElseThrow();
        TaskLeaseToken childLease = store.claimChildTask(parent, "task-a", "child-worker", now,
                Duration.ofSeconds(30)).orElseThrow();
        ChildTaskRecord activeChild = store.find(run.runId()).orElseThrow().childTasks().getFirst()
                .start(List.of(com.example.agent.model.Message.user("worker")), now);
        assertTrue(store.saveChildTaskFenced(parent, childLease, activeChild, now));

        RunRecord terminalParent = store.find(run.runId()).orElseThrow();
        terminalParent.transitionTo(RunStatus.CANCELLED, now.plusSeconds(1), FailureCategory.CANCELLED);
        assertTrue(store.saveFenced(terminalParent, parent, now.plusSeconds(1)));
        ChildTaskRecord settled = activeChild.finish(TaskStatus.INDETERMINATE, null,
                FailureCategory.TOOL_TIMEOUT_UNKNOWN, now.plusSeconds(2));

        assertTrue(store.saveChildTaskFenced(parent, null, settled, now.plusSeconds(2)));
        assertEquals(TaskStatus.INDETERMINATE, store.find(run.runId()).orElseThrow()
                .childTasks().getFirst().status());
        assertFalse(store.isChildTaskLeaseCurrent(childLease, parent, now.plusSeconds(3)));
    }

    @Test
    void activeChildTaskLeaseCanBeRenewedBeforeItExpires() {
        RunRecord run = collaborativeRun("parent-child-renewal");
        store.save(run);
        Instant now = Instant.now();
        LeaseToken parent = store.claim(run.runId(), "supervisor", now, Duration.ofMinutes(1)).orElseThrow();
        TaskLeaseToken task = store.claimChildTask(parent, "task-a", "child-worker", now,
                Duration.ofSeconds(10)).orElseThrow();

        assertTrue(store.renewChildTaskLease(parent, task, now.plusSeconds(5), Duration.ofSeconds(30)));
        assertTrue(store.claimChildTask(parent, "task-a", "other-worker", now.plusSeconds(11),
                Duration.ofSeconds(10)).isEmpty());
        assertTrue(store.isChildTaskLeaseCurrent(task, parent, now.plusSeconds(20)));
    }

    @Test
    void childTaskLeaseCanBeReclaimedImmediatelyAfterParentRunIsRefenced() {
        RunRecord run = collaborativeRun("parent-refenced-child");
        store.save(run);
        Instant now = Instant.parse("2026-11-12T00:00:00Z");
        LeaseToken previousParent = store.claim(run.runId(), "supervisor-a", now, Duration.ofSeconds(10)).orElseThrow();
        TaskLeaseToken previousTask = store.claimChildTask(previousParent, "task-a", "child-a", now,
                Duration.ofMinutes(2)).orElseThrow();

        LeaseToken recoveredParent = store.claim(run.runId(), "supervisor-b", now.plusSeconds(11),
                Duration.ofMinutes(2)).orElseThrow();
        TaskLeaseToken recoveredTask = store.claimChildTask(recoveredParent, "task-a", "child-b",
                now.plusSeconds(12), Duration.ofMinutes(2)).orElseThrow();

        assertTrue(recoveredTask.fencingToken() > previousTask.fencingToken());
        assertFalse(store.isChildTaskLeaseCurrent(previousTask, previousParent, now.plusSeconds(12)));
        assertTrue(store.isChildTaskLeaseCurrent(recoveredTask, recoveredParent, now.plusSeconds(12)));
    }

    @Test
    void runBudgetReservationsAreAtomicIdempotentAndFenced() throws Exception {
        RunRecord run = collaborativeRun("budget-reservations");
        store.save(run);
        Instant now = Instant.parse("2026-11-12T00:00:00Z");
        LeaseToken parent = store.claim(run.runId(), "supervisor-a", now, Duration.ofSeconds(10)).orElseThrow();
        TaskLeaseToken child = store.claimChildTask(parent, "task-a", "child-a", now,
                Duration.ofSeconds(10)).orElseThrow();
        RunRecord staleCheckpoint = store.find(run.runId()).orElseThrow();
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        var reserved = new AtomicInteger();
        var acceptedIds = new java.util.concurrent.CopyOnWriteArrayList<String>();

        try (var pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                int id = i;
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    String reservationId = "model-" + id;
                    if (store.reserveBudget(parent, child, BudgetKind.MODEL_CALL,
                            List.of(reservationId), 3, now.plusSeconds(1))) {
                        reserved.incrementAndGet();
                        acceptedIds.add(reservationId);
                    }
                    return null;
                });
            }
            ready.await();
            start.countDown();
        }

        assertEquals(3, reserved.get());
        assertTrue(store.reserveBudget(parent, child, BudgetKind.MODEL_CALL,
                List.of(acceptedIds.getFirst()), 3, now.plusSeconds(2)));
        assertFalse(store.reserveBudget(parent, child, BudgetKind.TOOL_CALL,
                List.of(acceptedIds.getFirst()), 3, now.plusSeconds(2)), "A reservation ID cannot change kind");
        assertTrue(store.reserveBudget(parent, child, BudgetKind.TOOL_CALL,
                List.of("tool-a", "tool-b"), 2, now.plusSeconds(2)));
        assertTrue(store.reserveBudget(parent, child, BudgetKind.TOOL_CALL,
                List.of("tool-a", "tool-b"), 2, now.plusSeconds(3)), "Retries must not double charge a reservation");
        assertFalse(store.reserveBudget(parent, child, BudgetKind.TOOL_CALL,
                List.of("tool-c"), 2, now.plusSeconds(3)));
        assertTrue(store.saveFenced(staleCheckpoint, parent, now.plusSeconds(4)));
        assertEquals(3, store.find(run.runId()).orElseThrow().modelCalls());
        assertEquals(2, store.find(run.runId()).orElseThrow().toolCalls());

        LeaseToken recoveredParent = store.claim(run.runId(), "supervisor-b", now.plusSeconds(11),
                Duration.ofSeconds(10)).orElseThrow();
        assertFalse(store.reserveBudget(parent, child, BudgetKind.MODEL_CALL,
                List.of("stale-model"), 5, now.plusSeconds(12)));
        TaskLeaseToken recoveredChild = store.claimChildTask(recoveredParent, "task-a", "child-b",
                now.plusSeconds(12), Duration.ofSeconds(10)).orElseThrow();
        assertTrue(store.reserveBudget(recoveredParent, recoveredChild, BudgetKind.MODEL_CALL,
                List.of("recovered-model"), 5, now.plusSeconds(13)));
    }

    @Test
    void childTaskCannotBeClaimedWithoutCurrentParentRunLease() {
        RunRecord run = collaborativeRun("parent-required");
        store.save(run);

        assertTrue(store.claimChildTask(new LeaseToken(run.runId(), "stale", 1), "task-a", "worker-a",
                Instant.now(), Duration.ofSeconds(10)).isEmpty());
    }

    @Test
    void onlyOneConcurrentWorkerCanClaimChildTask() throws Exception {
        RunRecord run = collaborativeRun("child-claim-race");
        store.save(run);
        Instant now = Instant.parse("2026-11-12T00:00:00Z");
        LeaseToken parentLease = store.claim(run.runId(), "supervisor", now, Duration.ofMinutes(2)).orElseThrow();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var claims = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                int worker = i;
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (store.claimChildTask(parentLease, "task-a", "worker-" + worker, now,
                            Duration.ofSeconds(10)).isPresent()) claims.incrementAndGet();
                    return null;
                });
            }
            ready.await();
            start.countDown();
        }
        assertEquals(1, claims.get());
    }

    private static RunRecord collaborativeRun(String runId) {
        RunRecord run = RunRecord.create(runId, "delegate", Duration.ofMinutes(2), RunType.COLLABORATIVE);
        PlannedTask task = new PlannedTask("task-a", "worker-a", "do work", java.util.List.of(), java.util.List.of("result"));
        run.setTaskPlan(new TaskPlan(TaskPlan.Route.DELEGATE, java.util.List.of(task), java.util.List.of()));
        run.addChildTask(ChildTaskRecord.pending(task));
        return run;
    }
}
