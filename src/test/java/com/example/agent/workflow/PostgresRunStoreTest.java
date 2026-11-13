package com.example.agent.workflow;

import com.example.agent.model.Message;
import com.example.agent.collaboration.ChildTaskRecord;
import com.example.agent.collaboration.PlannedTask;
import com.example.agent.collaboration.TaskPlan;
import com.example.agent.collaboration.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class PostgresRunStoreTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;
    private static PostgresRunStore store;

    @BeforeAll
    static void initializeDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).load().migrate();
        store = new PostgresRunStore(jdbc, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void persistsRunCheckpointAndQueryableState() {
        RunRecord run = RunRecord.create("pg-roundtrip", "calculate", Duration.ofMinutes(2));
        run.addMessage(Message.user("calculate"));
        store.save(run);

        RunRecord loaded = store.find("pg-roundtrip").orElseThrow();
        assertEquals(RunType.SINGLE_AGENT, loaded.runType());
        assertEquals(RunStatus.CREATED, loaded.status());
        assertEquals("calculate", loaded.userInput());
        assertEquals(1, loaded.messages().size());
        assertTrue(jdbc.queryForObject("select count(*) from agent_run", Integer.class) >= 1);
    }

    @Test
    void appendsSanitizedToolAuditEventsOnlyWhenToolStepStateChanges() {
        String runId = "pg-tool-audit-" + java.util.UUID.randomUUID();
        Instant now = Instant.now();
        RunRecord run = RunRecord.create(runId, "tool task", Duration.ofMinutes(2));
        RunStep pending = RunStep.toolPending("step-audit", "call-audit", "calculator", now);
        run.addStep(pending);
        store.save(run);

        LeaseToken lease = store.claim(runId, "audit-worker", now, Duration.ofMinutes(1)).orElseThrow();
        RunRecord active = store.find(runId).orElseThrow();
        active.replaceStep("step-audit", pending.start(now.plusMillis(10)));
        assertTrue(store.saveFenced(active, lease, now.plusMillis(10)));

        RunRecord completed = store.find(runId).orElseThrow();
        completed.replaceStep("step-audit", completed.step("step-audit")
                .finish(RunStepStatus.SUCCEEDED, now.plusMillis(25), null, null, null));
        assertTrue(store.saveFenced(completed, lease, now.plusMillis(25)));
        assertTrue(store.saveFenced(completed, lease, now.plusMillis(26)));

        var events = store.findToolAudit(runId);
        assertEquals(java.util.List.of(RunStepStatus.PENDING, RunStepStatus.IN_PROGRESS, RunStepStatus.SUCCEEDED),
                events.stream().map(ToolExecutionAudit::status).toList());
        assertTrue(events.stream().allMatch(event -> event.runId().equals(runId)
                && event.stepId().equals("step-audit") && event.toolCallId().equals("call-audit")
                && event.toolName().equals("calculator") && event.taskId() == null
                && event.failureCategory() == null && event.occurredAt() != null));
        assertEquals(3, jdbc.queryForObject("select count(*) from tool_execution_audit where run_id = ?",
                Integer.class, runId));
    }

    @Test
    void appendsToolAuditForFencedChildTaskCheckpoints() {
        String runId = "pg-child-tool-audit-" + java.util.UUID.randomUUID();
        Instant now = Instant.now();
        PlannedTask planned = new PlannedTask("task-audit", "worker-audit", "use calculator",
                java.util.List.of(), java.util.List.of("result"));
        RunRecord parent = RunRecord.create(runId, "calculate", Duration.ofMinutes(2), RunType.COLLABORATIVE);
        parent.setTaskPlan(new TaskPlan(TaskPlan.Route.DELEGATE, java.util.List.of(planned), java.util.List.of()));
        parent.addChildTask(ChildTaskRecord.pending(planned));
        parent.transitionTo(RunStatus.RUNNING, now, null);
        store.save(parent);
        LeaseToken parentLease = store.claim(runId, "supervisor-audit", now, Duration.ofMinutes(1)).orElseThrow();
        TaskLeaseToken taskLease = store.claimChildTask(parentLease, planned.taskId(), "worker-audit", now,
                Duration.ofSeconds(30)).orElseThrow();

        RunStep pending = RunStep.toolPending("child-tool-step", "child-call", "calculator", now,
                planned.taskId(), planned.workerId());
        ChildTaskRecord active = store.find(runId).orElseThrow().childTasks().getFirst()
                .start(java.util.List.of(Message.user("4 * 5")), now).withSteps(java.util.List.of(pending));
        assertTrue(store.saveChildTaskFenced(parentLease, taskLease, active, now));
        ChildTaskRecord started = active.withSteps(java.util.List.of(pending.start(now.plusMillis(5))));
        assertTrue(store.saveChildTaskFenced(parentLease, taskLease, started, now.plusMillis(5)));

        var events = store.findToolAudit(runId);
        assertEquals(java.util.List.of(RunStepStatus.PENDING, RunStepStatus.IN_PROGRESS),
                events.stream().map(ToolExecutionAudit::status).toList());
        assertTrue(events.stream().allMatch(event -> planned.taskId().equals(event.taskId())
                && planned.workerId().equals(event.workerId())));
    }

    @Test
    void childTaskCheckpointSurvivesAStaleParentCheckpointWrite() {
        Instant now = Instant.now();
        PlannedTask planned = new PlannedTask("isolated-task", "writer", "write a result", java.util.List.of(),
                java.util.List.of("result"));
        RunRecord parent = RunRecord.create("pg-child-checkpoint-isolation", "parent request",
                Duration.ofMinutes(2), RunType.COLLABORATIVE);
        parent.setTaskPlan(new TaskPlan(TaskPlan.Route.DELEGATE, java.util.List.of(planned), java.util.List.of()));
        parent.addChildTask(ChildTaskRecord.pending(planned));
        parent.transitionTo(RunStatus.RUNNING, now, null);
        store.save(parent);

        LeaseToken parentLease = store.claim(parent.runId(), "supervisor", now, Duration.ofMinutes(1)).orElseThrow();
        RunRecord staleParent = store.find(parent.runId()).orElseThrow();
        TaskLeaseToken taskLease = store.claimChildTask(parentLease, planned.taskId(), "task-worker", now,
                Duration.ofSeconds(30)).orElseThrow();
        RunStep workerStep = RunStep.workflow("worker-step-isolated", StepKind.WORKER,
                planned.taskId(), planned.workerId(), "WORKER", now);
        ChildTaskRecord taskCheckpoint = staleParent.childTasks().getFirst()
                .start(java.util.List.of(Message.user("task-local-message")), now)
                .withSteps(java.util.List.of(workerStep));

        assertTrue(store.saveChildTaskFenced(parentLease, taskLease, taskCheckpoint, now));
        staleParent.addMessage(Message.assistant("supervisor checkpoint advanced"));
        assertTrue(store.saveFenced(staleParent, parentLease, now.plusMillis(1)));

        RunRecord reloaded = store.find(parent.runId()).orElseThrow();
        ChildTaskRecord reloadedTask = reloaded.childTasks().getFirst();
        assertEquals(TaskStatus.IN_PROGRESS, reloadedTask.status());
        assertEquals("task-local-message", reloadedTask.messages().getFirst().content());
        assertEquals("supervisor checkpoint advanced", reloaded.messages().getLast().content());
        assertTrue(reloaded.steps().stream().anyMatch(step -> step.stepId().equals("worker-step-isolated")));
        assertEquals(java.util.List.of("worker-step-isolated"), reloadedTask.steps().stream()
                .map(RunStep::stepId).toList());
    }

    @Test
    void supervisorCanSettleActiveChildLeaseAfterParentRunBecomesTerminal() {
        Instant now = Instant.now();
        String runId = "pg-settle-terminal-child-" + java.util.UUID.randomUUID();
        PlannedTask planned = new PlannedTask("task-a", "worker-a", "work", java.util.List.of(), java.util.List.of());
        RunRecord parent = RunRecord.create(runId, "delegate", Duration.ofMinutes(2), RunType.COLLABORATIVE);
        parent.setTaskPlan(new TaskPlan(TaskPlan.Route.DELEGATE, java.util.List.of(planned), java.util.List.of()));
        parent.addChildTask(ChildTaskRecord.pending(planned));
        parent.transitionTo(RunStatus.RUNNING, now, null);
        store.save(parent);
        LeaseToken parentLease = store.claim(runId, "supervisor", now, Duration.ofMinutes(1)).orElseThrow();
        TaskLeaseToken taskLease = store.claimChildTask(parentLease, planned.taskId(), "task-worker", now,
                Duration.ofSeconds(30)).orElseThrow();
        ChildTaskRecord active = store.find(runId).orElseThrow().childTasks().getFirst()
                .start(java.util.List.of(Message.user("task")), now);
        assertTrue(store.saveChildTaskFenced(parentLease, taskLease, active, now));

        RunRecord cancelled = store.find(runId).orElseThrow();
        cancelled.transitionTo(RunStatus.CANCELLED, now.plusSeconds(1), FailureCategory.CANCELLED);
        assertTrue(store.saveFenced(cancelled, parentLease, now.plusSeconds(1)));
        ChildTaskRecord settled = active.finish(TaskStatus.INDETERMINATE, null,
                FailureCategory.TOOL_TIMEOUT_UNKNOWN, now.plusSeconds(2));

        assertTrue(store.saveChildTaskFenced(parentLease, null, settled, now.plusSeconds(2)));
        assertEquals(TaskStatus.INDETERMINATE,
                store.find(runId).orElseThrow().childTasks().getFirst().status());
        assertEquals(0, jdbc.queryForObject("select count(*) from child_task where run_id = ? " +
                "and task_lease_owner is not null", Integer.class, runId));
    }

    @Test
    void postgresFindsOnlyActiveCollaborativeRunsForTaskWorkers() {
        Instant now = Instant.now();
        String suffix = java.util.UUID.randomUUID().toString();
        RunRecord run = RunRecord.create("pg-active-child-" + suffix, "delegate", Duration.ofMinutes(2),
                RunType.COLLABORATIVE);
        PlannedTask planned = new PlannedTask("task-a", "worker-a", "work", java.util.List.of(), java.util.List.of());
        run.setTaskPlan(new TaskPlan(TaskPlan.Route.DELEGATE, java.util.List.of(planned), java.util.List.of()));
        run.addChildTask(ChildTaskRecord.pending(planned));
        run.transitionTo(RunStatus.RUNNING, now, null);
        store.save(run);
        store.claim(run.runId(), "supervisor", now, Duration.ofMinutes(1)).orElseThrow();

        java.util.List<RunRecord> candidates = store.findActiveCollaborativeRuns(now.plusSeconds(1), 10);

        assertEquals(java.util.List.of(run.runId()), candidates.stream().map(RunRecord::runId).toList());
        assertEquals("supervisor", candidates.getFirst().leaseOwner());
        assertEquals("task-a", candidates.getFirst().childTasks().getFirst().taskId());
    }

    @Test
    void postgresRecentRunListingReturnsOnlyTheNewestRequestedRows() {
        Instant base = Instant.parse("2099-11-12T00:00:00Z");
        for (int i = 1; i <= 4; i++) {
            RunRecord run = RunRecord.create("pg-recent-" + i, "task", Duration.ofMinutes(1));
            run.setCreatedAt(base.plusSeconds(i));
            store.save(run);
        }

        assertEquals(java.util.List.of("pg-recent-4", "pg-recent-3"), store.listRecent(2).stream()
                .map(RunRecord::runId).toList());
        assertTrue(store.list().stream().anyMatch(run -> run.runId().equals("pg-recent-1")));
        assertThrows(IllegalArgumentException.class, () -> store.listRecent(0));
    }

    @Test
    void databaseReturnsBoundedRecoverableRunsAndExcludesActiveLeases() {
        Instant now = Instant.parse("2026-11-12T00:00:00Z");
        RunRecord unclaimed = RunRecord.create("pg-recoverable-a", "task", Duration.ofMinutes(2));
        RunRecord leased = RunRecord.create("pg-recoverable-b", "task", Duration.ofMinutes(2));
        RunRecord terminal = RunRecord.create("pg-recoverable-c", "task", Duration.ofMinutes(2));
        terminal.transitionTo(RunStatus.RUNNING, now, null);
        terminal.transitionTo(RunStatus.FAILED, now.plusSeconds(1), FailureCategory.UNKNOWN);
        store.save(unclaimed);
        store.save(leased);
        store.save(terminal);
        store.claim(leased.runId(), "active-worker", now, Duration.ofSeconds(10));

        assertEquals(java.util.List.of("pg-recoverable-a"), store.findRecoverable(now.plusSeconds(5), 10).stream()
                .map(RunRecord::runId).filter(id -> id.startsWith("pg-recoverable-")).sorted().toList());
        assertEquals(java.util.List.of("pg-recoverable-a", "pg-recoverable-b"),
                store.findRecoverable(now.plusSeconds(11), 10).stream()
                        .map(RunRecord::runId).filter(id -> id.startsWith("pg-recoverable-")).sorted().toList());
        assertEquals(1, store.findRecoverable(now.plusSeconds(11), 1).size());
    }

    @Test
    void postgresQueueSnapshotCountsNewAndExpiredLeasesWithoutCountingActiveOnes() {
        Instant now = Instant.now();
        RunQueueSnapshot before = store.queueSnapshot(now);
        String suffix = java.util.UUID.randomUUID().toString();
        RunRecord created = RunRecord.create("pg-snapshot-created-" + suffix, "task", Duration.ofMinutes(2));
        RunRecord expired = RunRecord.create("pg-snapshot-expired-" + suffix, "task", Duration.ofMinutes(2));
        expired.transitionTo(RunStatus.RUNNING, Instant.now(), null);
        RunRecord active = RunRecord.create("pg-snapshot-active-" + suffix, "task", Duration.ofMinutes(2));
        active.transitionTo(RunStatus.RUNNING, Instant.now(), null);
        RunRecord recentFailure = RunRecord.create("pg-snapshot-failed-" + suffix, "task", Duration.ofMinutes(2));
        recentFailure.transitionTo(RunStatus.FAILED, now, FailureCategory.MODEL_NETWORK);
        RunRecord oldFailure = RunRecord.create("pg-snapshot-old-failed-" + suffix, "task", Duration.ofMinutes(2));
        oldFailure.transitionTo(RunStatus.FAILED, now, FailureCategory.MODEL_NETWORK);
        oldFailure.setUpdatedAt(now.minus(Duration.ofMinutes(6)));
        store.save(created);
        store.save(expired);
        store.save(active);
        store.save(recentFailure);
        store.save(oldFailure);
        store.claim(expired.runId(), "expired-owner", now.minusSeconds(20), Duration.ofSeconds(5));
        store.claim(active.runId(), "active-owner", now, Duration.ofMinutes(1));

        RunQueueSnapshot after = store.queueSnapshot(now);

        assertEquals(2, after.recoverableRuns() - before.recoverableRuns());
        assertEquals(1, after.expiredLeases() - before.expiredLeases());
        assertEquals(1, after.recentFailedRuns() - before.recentFailedRuns());
        assertTrue(after.oldestWait().compareTo(Duration.ZERO) >= 0);
    }

    @Test
    void databaseAllowsOnlyOneConcurrentClaimAndFencesExpiredOwner() throws Exception {
        RunRecord run = RunRecord.create("pg-fencing", "task", Duration.ofMinutes(1));
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
                    if (store.claim("pg-fencing", "worker-" + worker, now, Duration.ofSeconds(5)).isPresent()) {
                        claims.incrementAndGet();
                    }
                    return null;
                });
            }
            ready.await();
            start.countDown();
        }
        assertEquals(1, claims.get());

        LeaseToken oldToken = store.claim("pg-fencing", "worker-b", now.plusSeconds(6), Duration.ofSeconds(5)).orElseThrow();
        LeaseToken newerToken = store.claim("pg-fencing", "worker-c", now.plusSeconds(12), Duration.ofSeconds(5)).orElseThrow();
        RunRecord stale = store.find("pg-fencing").orElseThrow();
        RunRecord concurrentSnapshot = store.find("pg-fencing").orElseThrow();
        stale.setFinalAnswer("stale");
        assertFalse(store.saveFenced(stale, oldToken, now.plusSeconds(13)));
        stale.setFinalAnswer("current");
        assertTrue(store.saveFenced(stale, newerToken, now.plusSeconds(13)));
        concurrentSnapshot.setFinalAnswer("lost update");
        assertFalse(store.saveFenced(concurrentSnapshot, newerToken, now.plusSeconds(14)));
        assertEquals("current", store.find("pg-fencing").orElseThrow().finalAnswer());
    }

    @Test
    void cancellationRequestIsDurableAndDoesNotStealTheActiveLease() {
        RunRecord run = RunRecord.create("pg-cancel", "task", Duration.ofMinutes(1));
        store.save(run);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        LeaseToken owner = store.claim("pg-cancel", "worker-a", now, Duration.ofSeconds(30)).orElseThrow();

        assertTrue(store.requestCancellation("pg-cancel", now.plusSeconds(1)));
        assertTrue(store.find("pg-cancel").orElseThrow().cancellationRequested());
        assertTrue(store.claim("pg-cancel", "worker-b", now.plusSeconds(2), Duration.ofSeconds(30)).isEmpty());
        assertTrue(store.requestCancellation("pg-cancel", now.plusSeconds(2)));
    }

    @Test
    void childTaskClaimIsAtomicAndFencedByParentLease() {
        RunRecord run = RunRecord.create("pg-child-lease", "delegate", Duration.ofMinutes(2), RunType.COLLABORATIVE);
        PlannedTask task = new PlannedTask("task-a", "worker-a", "do work", java.util.List.of(), java.util.List.of("result"));
        run.setTaskPlan(new TaskPlan(TaskPlan.Route.DELEGATE, java.util.List.of(task), java.util.List.of()));
        run.addChildTask(ChildTaskRecord.pending(task));
        store.save(run);
        Instant now = Instant.parse("2026-11-12T00:00:00Z");
        LeaseToken parentLease = store.claim(run.runId(), "supervisor-a", now, Duration.ofMinutes(2)).orElseThrow();

        TaskLeaseToken first = store.claimChildTask(parentLease, "task-a", "worker-instance-a", now,
                Duration.ofSeconds(10)).orElseThrow();
        assertTrue(store.claimChildTask(parentLease, "task-a", "worker-instance-b", now.plusSeconds(1),
                Duration.ofSeconds(10)).isEmpty());
        assertTrue(store.claimChildTask(new LeaseToken(run.runId(), "old-supervisor", parentLease.fencingToken()),
                "task-a", "worker-instance-c", now.plusSeconds(2), Duration.ofSeconds(10)).isEmpty());

        TaskLeaseToken reclaimed = store.claimChildTask(parentLease, "task-a", "worker-instance-b",
                now.plusSeconds(11), Duration.ofSeconds(10)).orElseThrow();
        assertTrue(reclaimed.fencingToken() > first.fencingToken());
        assertEquals("worker-instance-b", reclaimed.ownerId());
        assertFalse(store.isChildTaskLeaseCurrent(first, parentLease, now.plusSeconds(12)));
        assertTrue(store.isChildTaskLeaseCurrent(reclaimed, parentLease, now.plusSeconds(12)));
    }

    @Test
    void postgresChildTaskLeaseRenewalExtendsCurrentFenceOnly() {
        RunRecord run = RunRecord.create("pg-child-renewal", "delegate", Duration.ofMinutes(2), RunType.COLLABORATIVE);
        PlannedTask task = new PlannedTask("task-a", "worker-a", "do work", java.util.List.of(), java.util.List.of());
        run.setTaskPlan(new TaskPlan(TaskPlan.Route.DELEGATE, java.util.List.of(task), java.util.List.of()));
        run.addChildTask(ChildTaskRecord.pending(task));
        run.transitionTo(RunStatus.RUNNING, Instant.now(), null);
        store.save(run);
        Instant now = Instant.now();
        LeaseToken parent = store.claim(run.runId(), "supervisor", now, Duration.ofMinutes(1)).orElseThrow();
        TaskLeaseToken child = store.claimChildTask(parent, "task-a", "child-a", now,
                Duration.ofSeconds(10)).orElseThrow();

        assertTrue(store.renewChildTaskLease(parent, child, now.plusSeconds(5), Duration.ofSeconds(30)));
        assertTrue(store.isChildTaskLeaseCurrent(child, parent, now.plusSeconds(20)));
        assertFalse(store.renewChildTaskLease(new LeaseToken(run.runId(), "stale", parent.fencingToken()),
                child, now.plusSeconds(21), Duration.ofSeconds(30)));
    }

    @Test
    void childTaskLeaseCanBeReclaimedBeforeItsExpiryWhenParentFenceChanges() {
        RunRecord run = RunRecord.create("pg-child-parent-refence", "delegate", Duration.ofMinutes(5),
                RunType.COLLABORATIVE);
        PlannedTask task = new PlannedTask("task-a", "worker-a", "do work", java.util.List.of(), java.util.List.of());
        run.setTaskPlan(new TaskPlan(TaskPlan.Route.DELEGATE, java.util.List.of(task), java.util.List.of()));
        run.addChildTask(ChildTaskRecord.pending(task));
        store.save(run);
        Instant now = Instant.parse("2026-11-12T00:00:00Z");
        LeaseToken previousParent = store.claim(run.runId(), "supervisor-a", now,
                Duration.ofSeconds(10)).orElseThrow();
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
    void independentStoreInstancesRecoverAbandonedParentAndChildLeases() {
        String runId = "pg-independent-recovery-" + java.util.UUID.randomUUID();
        PlannedTask planned = new PlannedTask("task-a", "worker-a", "perform work", java.util.List.of(),
                java.util.List.of("result"));
        RunRecord run = RunRecord.create(runId, "delegate", Duration.ofMinutes(5), RunType.COLLABORATIVE);
        run.setTaskPlan(new TaskPlan(TaskPlan.Route.DELEGATE, java.util.List.of(planned), java.util.List.of()));
        run.addChildTask(ChildTaskRecord.pending(planned));
        run.transitionTo(RunStatus.RUNNING, Instant.parse("2026-11-12T00:00:00Z"), null);
        store.save(run);

        PostgresRunStore otherProcessStore = new PostgresRunStore(new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())),
                new ObjectMapper().findAndRegisterModules());
        Instant firstClaimAt = Instant.parse("2026-11-12T00:00:00Z");
        LeaseToken firstParent = store.claim(runId, "process-a", firstClaimAt, Duration.ofSeconds(10)).orElseThrow();
        TaskLeaseToken firstChild = store.claimChildTask(firstParent, planned.taskId(), "child-process-a",
                firstClaimAt, Duration.ofMinutes(1)).orElseThrow();
        ChildTaskRecord inProgress = store.find(runId).orElseThrow().childTasks().getFirst()
                .start(java.util.List.of(Message.user("worker context")), firstClaimAt);
        assertTrue(store.saveChildTaskFenced(firstParent, firstChild, inProgress, firstClaimAt));

        Instant recoveredAt = firstClaimAt.plusSeconds(11);
        LeaseToken recoveredParent = otherProcessStore.claim(runId, "process-b", recoveredAt,
                Duration.ofSeconds(30)).orElseThrow();
        TaskLeaseToken recoveredChild = otherProcessStore.claimChildTask(recoveredParent, planned.taskId(),
                "child-process-b", recoveredAt, Duration.ofSeconds(30)).orElseThrow();
        ChildTaskRecord recoveredCheckpoint = otherProcessStore.find(runId).orElseThrow().childTasks().getFirst()
                .withMessages(java.util.List.of(Message.user("recovered context")));

        assertFalse(store.saveChildTaskFenced(firstParent, firstChild,
                inProgress.withMessages(java.util.List.of(Message.user("stale write"))), recoveredAt));
        assertTrue(otherProcessStore.saveChildTaskFenced(recoveredParent, recoveredChild,
                recoveredCheckpoint, recoveredAt));
        assertEquals("recovered context", store.find(runId).orElseThrow().childTasks().getFirst()
                .messages().getFirst().content());
        assertFalse(store.isChildTaskLeaseCurrent(firstChild, firstParent, recoveredAt));
    }

    @Test
    void postgresBudgetReservationsAreAtomicIdempotentAndFenced() throws Exception {
        RunRecord run = RunRecord.create("pg-budget-reservations", "delegate", Duration.ofMinutes(5),
                RunType.COLLABORATIVE);
        PlannedTask task = new PlannedTask("task-a", "worker-a", "do work", java.util.List.of(), java.util.List.of());
        run.setTaskPlan(new TaskPlan(TaskPlan.Route.DELEGATE, java.util.List.of(task), java.util.List.of()));
        run.addChildTask(ChildTaskRecord.pending(task));
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
                    if (store.reserveBudget(parent, child, BudgetKind.MODEL_CALL,
                            java.util.List.of("model-" + id), 3, now.plusSeconds(1))) {
                        reserved.incrementAndGet();
                        acceptedIds.add("model-" + id);
                    }
                    return null;
                });
            }
            ready.await();
            start.countDown();
        }

        assertEquals(3, reserved.get());
        assertTrue(store.reserveBudget(parent, child, BudgetKind.MODEL_CALL,
                java.util.List.of(acceptedIds.getFirst()), 3, now.plusSeconds(2)));
        assertFalse(store.reserveBudget(parent, child, BudgetKind.TOOL_CALL,
                java.util.List.of(acceptedIds.getFirst()), 3, now.plusSeconds(2)));
        assertTrue(store.reserveBudget(parent, child, BudgetKind.TOOL_CALL,
                java.util.List.of("tool-a", "tool-b"), 2, now.plusSeconds(2)));
        assertTrue(store.reserveBudget(parent, child, BudgetKind.TOOL_CALL,
                java.util.List.of("tool-a", "tool-b"), 2, now.plusSeconds(3)));
        assertFalse(store.reserveBudget(parent, child, BudgetKind.TOOL_CALL,
                java.util.List.of("tool-c"), 2, now.plusSeconds(3)));
        assertTrue(store.saveFenced(staleCheckpoint, parent, now.plusSeconds(4)));
        assertEquals(3, store.find(run.runId()).orElseThrow().modelCalls());
        assertEquals(2, store.find(run.runId()).orElseThrow().toolCalls());
    }

    @Test
    void postgresAllowsOnlyOneConcurrentChildTaskClaim() throws Exception {
        RunRecord run = RunRecord.create("pg-child-race", "delegate", Duration.ofMinutes(2), RunType.COLLABORATIVE);
        PlannedTask task = new PlannedTask("task-a", "worker-a", "do work", java.util.List.of(), java.util.List.of());
        run.setTaskPlan(new TaskPlan(TaskPlan.Route.DELEGATE, java.util.List.of(task), java.util.List.of()));
        run.addChildTask(ChildTaskRecord.pending(task));
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

    @Test
    void checkpointAndQueryableProjectionRollBackTogetherOnProjectionFailure() {
        RunRecord run = RunRecord.create("pg-rollback", "task", Duration.ofMinutes(1));
        store.save(run);
        Instant now = Instant.parse("2026-11-12T00:00:00Z");
        LeaseToken lease = store.claim(run.runId(), "worker", now, Duration.ofSeconds(30)).orElseThrow();
        RunRecord changed = store.find(run.runId()).orElseThrow();
        changed.setFinalAnswer("must roll back");
        changed.addStep(RunStep.workflow("reject-this-projection", StepKind.PLAN, null, "supervisor", "PLAN", now));
        jdbc.execute("""
                CREATE FUNCTION reject_test_projection() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.step_id = 'reject-this-projection' THEN RAISE EXCEPTION 'test projection rejection'; END IF;
                  RETURN NEW;
                END; $$
                """);
        jdbc.execute("CREATE TRIGGER reject_test_projection_trigger BEFORE INSERT ON run_step "
                + "FOR EACH ROW EXECUTE FUNCTION reject_test_projection()");
        try {
            assertThrows(RuntimeException.class, () -> store.saveFenced(changed, lease, now.plusSeconds(1)));
            RunRecord persisted = store.find(run.runId()).orElseThrow();
            assertNull(persisted.finalAnswer());
            assertTrue(persisted.steps().isEmpty());
            assertEquals(0, persisted.version());
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_projection_trigger ON run_step");
            jdbc.execute("DROP FUNCTION reject_test_projection()");
        }
    }

    @Test
    void retentionDeletesOnlyExpiredTerminalRunsAndCascadesSensitiveProjections() {
        Instant completedAt = Instant.parse("2026-10-01T00:00:00Z");
        RunRecord expired = RunRecord.create("pg-retention-expired", "sensitive payload", Duration.ofDays(90),
                RunType.COLLABORATIVE);
        expired.transitionTo(RunStatus.RUNNING, completedAt.minusSeconds(1), null);
        expired.transitionTo(RunStatus.SUCCEEDED, completedAt, null);
        expired.addMessage(Message.user("sensitive message"));
        PlannedTask task = new PlannedTask("task-retention", "worker", "task payload", java.util.List.of(),
                java.util.List.of());
        expired.addChildTask(ChildTaskRecord.pending(task));
        expired.addStep(RunStep.workflow("step-retention", StepKind.PLAN, null,
                "supervisor", "PLAN", completedAt));
        expired.addStep(RunStep.toolPending("tool-retention", "call-retention", "weather", completedAt));
        store.save(expired);

        RunRecord active = RunRecord.create("pg-retention-active", "keep", Duration.ofDays(90));
        active.transitionTo(RunStatus.RUNNING, completedAt, null);
        store.save(active);

        assertEquals(1, store.purgeTerminalRunsBefore(Instant.parse("2026-11-01T00:00:00Z"), 10));
        assertTrue(store.find("pg-retention-expired").isEmpty());
        assertTrue(store.find("pg-retention-active").isPresent());
        assertEquals(0, jdbc.queryForObject("select count(*) from run_message where run_id = 'pg-retention-expired'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from run_step where run_id = 'pg-retention-expired'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from child_task where run_id = 'pg-retention-expired'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from tool_execution_audit where run_id = 'pg-retention-expired'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select deleted_runs from run_retention_audit order by audit_id desc limit 1", Integer.class));
    }
}
