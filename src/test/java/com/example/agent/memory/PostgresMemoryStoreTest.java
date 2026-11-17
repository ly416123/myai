package com.example.agent.memory;

import com.example.agent.test.PostgresTestContainerSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class PostgresMemoryStoreTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = PostgresTestContainerSupport.create();

    private static JdbcTemplate jdbc;
    private static PostgresMemoryStore store;

    @BeforeAll
    static void migrateAndCreateStore() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new PostgresMemoryStore(jdbc);
    }

    @BeforeEach
    void clearTables() {
        jdbc.execute("TRUNCATE TABLE agent_memory_event RESTART IDENTITY");
        jdbc.execute("TRUNCATE TABLE agent_memory");
    }

    @Test
    void updateUsesExpectedVersionAndAuditHasNoMemoryPayloadColumns() {
        var created = store.create("default", "answer.language", MemoryKind.PREFERENCE, "回答使用中文");

        var updated = store.update("default", created.memoryId(), created.version(),
                MemoryKind.PREFERENCE, "默认使用简体中文");

        assertTrue(updated.isPresent());
        assertEquals(2, updated.orElseThrow().version());
        assertTrue(store.update("default", created.memoryId(), created.version(),
                MemoryKind.PREFERENCE, "stale-value").isEmpty());
        assertEquals(2, store.events(created.memoryId()).size());
        Set<String> auditColumns = Set.copyOf(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'agent_memory_event'
                """, String.class));
        assertFalse(auditColumns.contains("content"));
        assertFalse(auditColumns.contains("memory_key"));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE agent_memory_event SET source = 'changed' WHERE memory_id = ?", created.memoryId()));
    }

    @Test
    void deleteTombstonesAndClearsKeyAndContentWhileRetainingMetadataAudit() {
        var created = store.create("default", "private.fact", MemoryKind.FACT, "private-memory-value");

        assertTrue(store.delete("default", created.memoryId(), created.version()));

        assertTrue(store.findByKey("default", "private.fact").isEmpty());
        assertEquals(0, store.countActive("default"));
        assertEquals(2, store.events(created.memoryId()).size());
        var tombstone = jdbc.queryForMap("SELECT status, memory_key, content FROM agent_memory WHERE memory_id = ?",
                created.memoryId());
        assertEquals("DELETED", tombstone.get("status"));
        assertTrue(tombstone.get("memory_key") == null);
        assertTrue(tombstone.get("content") == null);
    }

    @Test
    void activeKeyIsUniqueButMayBeRecreatedAfterDeletion() {
        var first = store.create("default", "answer.language", MemoryKind.PREFERENCE, "中文");
        assertThrows(DuplicateKeyException.class,
                () -> store.create("default", "answer.language", MemoryKind.PREFERENCE, "English"));

        assertTrue(store.delete("default", first.memoryId(), first.version()));
        assertEquals("English", store.create("default", "answer.language", MemoryKind.PREFERENCE,
                "English").content());
    }

    @Test
    void concurrentUpdatesWithSameVersionAllowOnlyOneWriter() throws Exception {
        var created = store.create("default", "answer.language", MemoryKind.PREFERENCE, "中文");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                return store.update("default", created.memoryId(), created.version(),
                        MemoryKind.PREFERENCE, "简体中文");
            });
            var second = pool.submit(() -> {
                start.await();
                return store.update("default", created.memoryId(), created.version(),
                        MemoryKind.PREFERENCE, "繁體中文");
            });
            start.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS).isPresent()
                    ^ second.get(5, TimeUnit.SECONDS).isPresent());
        }
        assertEquals(2, store.events(created.memoryId()).size());
    }

    @Test
    void updateAndAuditEventRollBackTogetherWhenAuditInsertFails() {
        var created = store.create("default", "answer.language", MemoryKind.PREFERENCE, "中文");
        jdbc.execute("""
                CREATE FUNCTION reject_memory_update_event() RETURNS trigger AS $$
                BEGIN
                    IF NEW.operation = 'UPDATE' THEN RAISE EXCEPTION 'forced test failure'; END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER reject_memory_update_event_trigger
                BEFORE INSERT ON agent_memory_event
                FOR EACH ROW EXECUTE FUNCTION reject_memory_update_event()
                """);

        try {
            assertThrows(MemoryStoreUnavailableException.class, () -> store.update("default", created.memoryId(),
                    created.version(), MemoryKind.PREFERENCE, "更新值"));
        } finally {
            jdbc.execute("DROP TRIGGER reject_memory_update_event_trigger ON agent_memory_event");
            jdbc.execute("DROP FUNCTION reject_memory_update_event()");
        }

        assertEquals("中文", store.findByKey("default", "answer.language").orElseThrow().content());
        assertEquals(1, store.events(created.memoryId()).size());
    }

    @Test
    void namespaceLimitIsAtomicAcrossConcurrentCreates() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> createWithinLimit(start, "first.key"));
            var second = pool.submit(() -> createWithinLimit(start, "second.key"));
            start.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS) ^ second.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, store.countActive("limited"));
    }

    private static boolean createWithinLimit(CountDownLatch start, String key) throws Exception {
        start.await();
        try {
            store.create("limited", key, MemoryKind.FACT, "value", 1);
            return true;
        } catch (MemoryLimitExceededException rejected) {
            return false;
        }
    }
}
