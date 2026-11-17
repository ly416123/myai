package com.example.agent.memory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL implementation; changes and metadata audit events commit atomically. */
public final class PostgresMemoryStore implements MemoryStore {
    private static final RowMapper<MemoryEntry> ENTRY_MAPPER = PostgresMemoryStore::readEntry;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PostgresMemoryStore(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        var dataSource = java.util.Objects.requireNonNull(jdbc.getDataSource(), "JdbcTemplate must have a DataSource");
        this.transactions = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
    }

    @Override
    public MemoryEntry create(String namespace, String key, MemoryKind kind, String content, int namespaceLimit) {
        if (namespaceLimit < 1 || namespaceLimit > 500) throw new IllegalArgumentException("Invalid memory limit");
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        MemoryEntry entry = new MemoryEntry(id, namespace, key, kind, content, 1, now, now);
        try {
            transactions.executeWithoutResult(status -> {
                jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, namespace);
                Boolean exists = jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM agent_memory
                                      WHERE namespace = ? AND memory_key = ? AND status = 'ACTIVE')
                        """, Boolean.class, namespace, key);
                if (Boolean.TRUE.equals(exists)) throw new DuplicateKeyException("Memory key already exists");
                if (countActive(namespace) >= namespaceLimit) throw new MemoryLimitExceededException();
                jdbc.update("""
                        INSERT INTO agent_memory(memory_id, namespace, memory_key, kind, content, version, status,
                                                 created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, 1, 'ACTIVE', ?, ?)
                        """, id, namespace, key, kind.name(), content, timestamp(now), timestamp(now));
                appendEvent(id, "CREATE", 1, now);
            });
        } catch (DuplicateKeyException duplicate) {
            throw duplicate;
        } catch (DataAccessException unavailable) {
            throw new MemoryStoreUnavailableException();
        }
        return entry;
    }

    @Override
    public Optional<MemoryEntry> update(String namespace, UUID memoryId, long expectedVersion,
                                        MemoryKind kind, String content) {
        try {
            return transactions.execute(status -> {
                List<MemoryEntry> updated = jdbc.query("""
                    UPDATE agent_memory
                    SET kind = ?, content = ?, version = version + 1, updated_at = now()
                    WHERE namespace = ? AND memory_id = ? AND version = ? AND status = 'ACTIVE'
                    RETURNING memory_id, namespace, memory_key, kind, content, version, created_at, updated_at
                    """, ENTRY_MAPPER, kind.name(), content, namespace, memoryId, expectedVersion);
            if (updated.isEmpty()) return Optional.empty();
            MemoryEntry entry = updated.getFirst();
            appendEvent(memoryId, "UPDATE", entry.version(), entry.updatedAt());
                return Optional.of(entry);
            });
        } catch (DataAccessException unavailable) {
            throw new MemoryStoreUnavailableException();
        }
    }

    @Override
    public boolean delete(String namespace, UUID memoryId, long expectedVersion) {
        try {
            return transactions.execute(status -> {
                List<MemoryEntry> current = jdbc.query("""
                    SELECT memory_id, namespace, memory_key, kind, content, version, created_at, updated_at
                    FROM agent_memory
                    WHERE namespace = ? AND memory_id = ? AND version = ? AND status = 'ACTIVE'
                    FOR UPDATE
                    """, ENTRY_MAPPER, namespace, memoryId, expectedVersion);
            if (current.isEmpty()) return false;
            Instant now = Instant.now();
            long nextVersion = expectedVersion + 1;
            jdbc.update("""
                    UPDATE agent_memory
                    SET memory_key = NULL, content = NULL, status = 'DELETED', version = ?, updated_at = ?
                    WHERE namespace = ? AND memory_id = ? AND version = ? AND status = 'ACTIVE'
                    """, nextVersion, timestamp(now), namespace, memoryId, expectedVersion);
            appendEvent(memoryId, "DELETE", nextVersion, now);
                return true;
            });
        } catch (DataAccessException unavailable) {
            throw new MemoryStoreUnavailableException();
        }
    }

    @Override
    public Optional<MemoryEntry> findByKey(String namespace, String key) {
        try {
            return jdbc.query("""
                    SELECT memory_id, namespace, memory_key, kind, content, version, created_at, updated_at
                    FROM agent_memory WHERE namespace = ? AND memory_key = ? AND status = 'ACTIVE'
                    """, ENTRY_MAPPER, namespace, key).stream().findFirst();
        } catch (DataAccessException unavailable) {
            throw new MemoryStoreUnavailableException();
        }
    }

    @Override
    public List<MemoryEntry> listActive(String namespace, int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("Invalid active memory limit");
        try {
            return jdbc.query("""
                    SELECT memory_id, namespace, memory_key, kind, content, version, created_at, updated_at
                    FROM agent_memory WHERE namespace = ? AND status = 'ACTIVE'
                    ORDER BY memory_id LIMIT ?
                    """, ENTRY_MAPPER, namespace, limit);
        } catch (DataAccessException unavailable) {
            throw new MemoryStoreUnavailableException();
        }
    }

    @Override
    public int countActive(String namespace) {
        try {
            Integer count = jdbc.queryForObject("""
                    SELECT count(*) FROM agent_memory WHERE namespace = ? AND status = 'ACTIVE'
                    """, Integer.class, namespace);
            return count == null ? 0 : count;
        } catch (DataAccessException unavailable) {
            throw new MemoryStoreUnavailableException();
        }
    }

    @Override
    public List<MemoryEvent> events(UUID memoryId) {
        try {
            return jdbc.query("""
                    SELECT event_id, memory_id, operation, version, occurred_at, source
                    FROM agent_memory_event WHERE memory_id = ? ORDER BY event_id
                    """, (rs, row) -> new MemoryEvent(rs.getLong("event_id"), rs.getObject("memory_id", UUID.class),
                    rs.getString("operation"), rs.getLong("version"), instant(rs.getTimestamp("occurred_at")),
                    rs.getString("source")), memoryId);
        } catch (DataAccessException unavailable) {
            throw new MemoryStoreUnavailableException();
        }
    }

    private void appendEvent(UUID id, String operation, long version, Instant now) {
        jdbc.update("""
                INSERT INTO agent_memory_event(memory_id, operation, version, occurred_at, source)
                VALUES (?, ?, ?, ?, 'EXPLICIT_CLI')
                """, id, operation, version, timestamp(now));
    }

    private static MemoryEntry readEntry(ResultSet rs, int row) throws SQLException {
        return new MemoryEntry(rs.getObject("memory_id", UUID.class), rs.getString("namespace"),
                rs.getString("memory_key"), MemoryKind.valueOf(rs.getString("kind")), rs.getString("content"),
                rs.getLong("version"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
