package com.example.agent.configuration;

import com.example.agent.test.PostgresTestContainerSupport;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class PostgresAgentConfigStoreTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = PostgresTestContainerSupport.create();
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static JdbcTemplate jdbc;
    private static PostgresAgentConfigStore store;

    @BeforeAll
    static void initializeDatabase() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).load().migrate();
        store = new PostgresAgentConfigStore(jdbc, MAPPER);
    }

    @Test
    void persistsImmutableRevisionsAndReleaseHistory() {
        var revision = revision("Prompt one");
        store.saveRevision(revision, "a1b2c3d", "ci-release", Instant.now());
        store.saveRevision(revision, "a1b2c3d", "ci-release", Instant.now());
        var release = release("rel-store-test", revision, null, null);
        store.saveRelease(release);

        assertEquals(revision, store.findRevision(revision.revisionId()).orElseThrow());
        assertEquals(release, store.findRelease(release.releaseId()).orElseThrow());
        assertEquals(1, store.listRevisions(10).stream()
                .filter(item -> item.revisionId().equals(revision.revisionId())).count());
        assertThrows(IllegalStateException.class, () -> store.saveRelease(new AgentConfigRelease(
                release.releaseId(), revision.revisionId(), revision.contentHash(), null, null,
                MAPPER.createObjectNode().put("passed", false), "a1b2c3d", "changed", "ci-release", Instant.now())));
    }

    @Test
    void assignmentUsesCompareAndSetAndAppendsAnAuditEventAtomically() {
        var revision = revision("Prompt assignment");
        store.saveRevision(revision, "a1b2c3d", "ci-release", Instant.now());
        var first = release("rel-assignment-a", revision, null, null);
        var second = release("rel-assignment-b", revision, null, null);
        store.saveRelease(first);
        store.saveRelease(second);
        Instant now = Instant.now();

        assertTrue(store.assign("test", "assistant", first.releaseId(), null, 0, 0,
                ReleaseAction.ACTIVATE, "ci-release", "initial assignment", now));
        assertFalse(store.assign("test", "assistant", second.releaseId(), null, 0, 0,
                ReleaseAction.ACTIVATE, "ci-release", "stale update", now.plusSeconds(1)));
        assertEquals(first.releaseId(), store.assignment("test", "assistant").orElseThrow().activeReleaseId());
        assertEquals(1, store.events("test", "assistant", 20).size());
    }

    @Test
    void onlyOneConcurrentActivationCanWinTheSameExpectedVersion() throws Exception {
        var revision = revision("Prompt concurrency");
        store.saveRevision(revision, "a1b2c3d", "ci-release", Instant.now());
        var initial = release("rel-concurrency-base", revision, null, null);
        var left = release("rel-concurrency-left", revision, null, null);
        var right = release("rel-concurrency-right", revision, null, null);
        store.saveRelease(initial);
        store.saveRelease(left);
        store.saveRelease(right);
        Instant now = Instant.now();
        assertTrue(store.assign("test", "concurrent", initial.releaseId(), null, 0, 0,
                ReleaseAction.ACTIVATE, "test", "initial", now));

        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> assignAfterBarrier(ready, start, left.releaseId(), now));
            var second = pool.submit(() -> assignAfterBarrier(ready, start, right.releaseId(), now));
            ready.await();
            start.countDown();
            assertNotEquals(first.get(), second.get());
        }

        var assignment = store.assignment("test", "concurrent").orElseThrow();
        assertEquals(2, assignment.version());
        assertEquals(2, store.events("test", "concurrent", 20).size());
    }

    private boolean assignAfterBarrier(CountDownLatch ready, CountDownLatch start, String releaseId, Instant now)
            throws InterruptedException {
        ready.countDown();
        start.await();
        return store.assign("test", "concurrent", releaseId, null, 0, 1,
                ReleaseAction.ACTIVATE, "test", "concurrent activation", now.plusMillis(1));
    }

    private static AgentConfigRevision revision(String prompt) {
        return AgentConfigRevision.create(new AgentConfigDocument(1, prompt, List.of(),
                new ModelSettings("deepseek", "deepseek-flash", Map.of("temperature", new BigDecimal("0.2")),
                        "secret://providers/deepseek"), Set.of("calculator"), Map.of(),
                new RuntimeSettings(10, 20, 2, 300_000, 8, 2)), MAPPER);
    }

    private AgentConfigRelease release(String id, AgentConfigRevision revision, String evaluationId, String baselineId) {
        return new AgentConfigRelease(id, revision.revisionId(), revision.contentHash(), evaluationId, baselineId,
                MAPPER.createObjectNode().put("passed", true).put("policyVersion", "v1"),
                "a1b2c3d", "test release", "test-operator", Instant.now());
    }
}
