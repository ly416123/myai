package com.example.agent.evaluation;

import com.example.agent.test.PostgresTestContainerSupport;
import com.example.agent.workflow.RunRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class PostgresEvaluationRepositoryTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = PostgresTestContainerSupport.create();
    private static JdbcTemplate jdbc;
    private static PostgresEvaluationRepository repository;

    @BeforeAll
    static void initializeDatabase() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).load().migrate();
        repository = new PostgresEvaluationRepository(jdbc, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void persistsExperimentAndCaseScoresIncrementally() {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        repository.create(run(id, now), List.of("case-a"));
        var lease = start(id, now);

        assertTrue(repository.startCase(lease, "case-a", now.plusMillis(2)));
        String runId = "evaluation-run-" + UUID.randomUUID();
        new com.example.agent.workflow.PostgresRunStore(jdbc, new ObjectMapper().findAndRegisterModules())
                .save(RunRecord.create(runId, "synthetic evaluation input", Duration.ofMinutes(1)));
        var caseRecord = completedCase(id, "case-a", runId, now.plusMillis(2));
        assertTrue(repository.saveCase(lease, caseRecord, now.plusMillis(5)));
        assertFalse(repository.saveCase(lease, caseRecord, now.plusMillis(6)), "terminal case result must not be counted twice");
        assertTrue(repository.finish(lease, EvaluationRunStatus.SUCCEEDED, aggregate(), now.plusMillis(10), null));

        var saved = repository.find(id).orElseThrow();
        assertEquals("agent-runtime", saved.run().dataset().datasetId());
        assertEquals(EvaluationRunStatus.SUCCEEDED, saved.run().status());
        assertEquals(1, saved.run().aggregate().completedCases());
        assertEquals(1, saved.cases().size());
        assertEquals(EvaluationOutcome.PASS, saved.cases().getFirst().scores().getFirst().outcome());
        assertEquals(runId, saved.cases().getFirst().runId());
        assertEquals(List.of(EvaluationRunStatus.CREATED, EvaluationRunStatus.RUNNING, EvaluationRunStatus.SUCCEEDED),
                saved.statusEvents().stream().map(EvaluationStatusEvent::status).toList());
    }

    @Test
    void onlyOneProcessCanStartAnEvaluationAndTerminalRunsCannotBeRewritten() {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        repository.create(run(id, now), List.of("case-a"));

        var lease = start(id, now);
        assertTrue(lease.fence() > 0);
        assertTrue(repository.finish(lease, EvaluationRunStatus.FAILED, EvaluationAggregate.empty(),
                now.plusMillis(3), "TEST_FAILURE"));
        assertFalse(repository.finish(lease, EvaluationRunStatus.SUCCEEDED, aggregate(), now.plusMillis(4), null));
        assertEquals(EvaluationRunStatus.FAILED, repository.find(id).orElseThrow().run().status());
    }

    @Test
    void concurrentClaimAllowsOnlyOneEvaluationWorker() throws Exception {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        repository.create(run(id, now), List.of("case-a"));
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> claimAtBarrier(id, now, ready, start));
            var second = pool.submit(() -> claimAtBarrier(id, now, ready, start));
            ready.await(); start.countDown();
            assertEquals(1, (first.get() != null ? 1 : 0) + (second.get() != null ? 1 : 0));
        }
    }

    private static EvaluationLease claimAtBarrier(String id, Instant now,
            java.util.concurrent.CountDownLatch ready, java.util.concurrent.CountDownLatch start) throws Exception {
        ready.countDown(); start.await(); return repository.start(id, UUID.randomUUID().toString(),
                now.plusSeconds(45), now.plusMillis(1)).orElse(null);
    }

    @Test
    void deletingLinkedRunPreservesEvaluationScoreAndClearsOnlyRunReference() {
        String id = UUID.randomUUID().toString();
        String runId = "evaluation-run-" + UUID.randomUUID();
        Instant now = Instant.now();
        var agentRun = RunRecord.create(runId, "synthetic evaluation input", Duration.ofMinutes(1));
        new com.example.agent.workflow.PostgresRunStore(jdbc, new ObjectMapper().findAndRegisterModules()).save(agentRun);
        repository.create(run(id, now), List.of("case-a"));
        var lease = start(id, now);
        repository.startCase(lease, "case-a", now.plusMillis(2));
        repository.saveCase(lease, completedCase(id, "case-a", runId, now.plusMillis(2)), now.plusMillis(3));

        jdbc.update("DELETE FROM agent_run WHERE run_id = ?", runId);

        var saved = repository.find(id).orElseThrow().cases().getFirst();
        assertNull(saved.runId());
        assertEquals(EvaluationOutcome.PASS, saved.scores().getFirst().outcome());
    }

    @Test
    void interruptedEvaluationMarksUnknownCaseWithoutReplayingIt() {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        repository.create(run(id, now), List.of("case-a", "case-b"));
        var lease = start(id, now);

        repository.startCase(lease, "case-a", now.plusMillis(2));
        assertEquals(0, repository.recoverExpired(id, now.plusSeconds(30)), "live lease prevents recovery");
        assertEquals(2, repository.recoverExpired(id, now.plusSeconds(61)));
        var saved = repository.find(id).orElseThrow();
        assertEquals(EvaluationCaseStatus.INDETERMINATE, saved.cases().getFirst().status());
        assertEquals("INTERRUPTED_OUTCOME_UNKNOWN", saved.cases().getFirst().failureCategory());
        assertTrue(saved.cases().getFirst().scores().stream().allMatch(s -> s.outcome() == EvaluationOutcome.UNKNOWN));
        assertEquals(EvaluationCaseStatus.SKIPPED, saved.cases().getLast().status());
        assertTrue(saved.cases().getLast().scores().stream().allMatch(s -> s.outcome() == EvaluationOutcome.UNKNOWN));
        assertEquals(EvaluationRunStatus.PARTIAL, saved.run().status());
        assertFalse(repository.startCase(lease, "case-b", now.plusSeconds(62)), "stale owner cannot advance a case");
        assertFalse(repository.saveCase(lease, completedCase(id, "case-a", null, now.plusSeconds(62)),
                now.plusSeconds(62)), "stale owner cannot overwrite an indeterminate case");
        assertFalse(repository.finish(lease, EvaluationRunStatus.SUCCEEDED, aggregate(), now.plusSeconds(62), null));
    }

    @Test
    void persistsCasesBeyondExecutionCaseBudgetAsPendingForExplicitSkip() {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        var limited = EvaluationRunRecord.created(id, new EvaluationDatasetIdentity("agent-runtime", "v1",
                "a".repeat(64), 1), "git-sha", "single", "fake", "test-model",
                EvaluationEvaluatorRegistry.defaults().versions(), 8, 4, 1, now, now.plusSeconds(60));
        repository.create(limited, List.of("case-a", "case-b"));
        var saved = repository.find(id).orElseThrow();
        assertEquals(2, saved.cases().size());
        assertEquals(2, saved.run().aggregate().totalCases());
    }

    private static EvaluationRunRecord run(String id, Instant now) {
        var dataset = new EvaluationDatasetIdentity("agent-runtime", "v1", "a".repeat(64), 1);
        return EvaluationRunRecord.created(id, dataset, "git-sha", "single", "fake", "test-model",
                EvaluationEvaluatorRegistry.defaults().versions(), 8, 4, 5, now, now.plusSeconds(60));
    }

    private static EvaluationLease start(String id, Instant now) {
        return repository.start(id, UUID.randomUUID().toString(), now.plusSeconds(45), now.plusMillis(1)).orElseThrow();
    }

    private static EvaluationCaseRecord completedCase(String id, String caseId, String runId, Instant started) {
        return new EvaluationCaseRecord(id, caseId, EvaluationCaseStatus.SUCCEEDED, runId, null,
                List.of(EvaluationScore.of(EvaluationEvaluatorIds.RUN_OUTCOME, "1", EvaluationOutcome.PASS,
                        "RUN_COMPLETED")), 2, 1, true, 20L, 5L, null,
                started, started.plusMillis(5), 5);
    }

    private static EvaluationAggregate aggregate() {
        return new EvaluationAggregate(1, 1, 1, 0, 2, 1, 20L, 5L, new BigDecimal("0.00100000"));
    }
}
