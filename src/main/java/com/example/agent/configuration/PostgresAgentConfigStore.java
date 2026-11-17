package com.example.agent.configuration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** PostgreSQL source of truth for immutable config revisions, releases, assignments and audit events. */
public final class PostgresAgentConfigStore implements AgentConfigStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public PostgresAgentConfigStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        if (jdbc == null || jdbc.getDataSource() == null || mapper == null) {
            throw new IllegalArgumentException("JDBC and JSON mapper are required");
        }
        this.jdbc = jdbc;
        this.mapper = mapper.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    @Override
    public void saveRevision(AgentConfigRevision revision, String sourceCommit, String createdBy, Instant createdAt) {
        if (revision == null || createdAt == null || createdBy == null || createdBy.isBlank()) {
            throw new IllegalArgumentException("Revision provenance is required");
        }
        AgentConfigRevision canonical = AgentConfigRevision.create(revision.document(), mapper);
        if (!canonical.equals(revision)) throw new IllegalArgumentException("Revision content does not match its identity");
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO agent_config_revision(revision_id, content_hash, format_version, payload,
                      source_commit, created_by, created_at)
                    VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)
                    ON CONFLICT (revision_id) DO NOTHING
                    """, revision.revisionId(), revision.contentHash(), revision.document().formatVersion(),
                    json(revision), sourceCommit, createdBy, timestamp(createdAt));
            AgentConfigRevision saved = findRevision(revision.revisionId()).orElseThrow();
            if (!saved.equals(revision)) throw new IllegalStateException("Configuration revision is immutable");
        });
    }

    @Override
    public Optional<AgentConfigRevision> findRevision(String revisionId) {
        return jdbc.query("SELECT payload::text FROM agent_config_revision WHERE revision_id = ?",
                rs -> rs.next() ? Optional.of(read(rs.getString(1), AgentConfigRevision.class)) : Optional.empty(), revisionId);
    }

    @Override
    public List<AgentConfigRevision> listRevisions(int limit) {
        requireLimit(limit);
        return jdbc.query("SELECT payload::text FROM agent_config_revision ORDER BY created_at DESC, revision_id LIMIT ?",
                (rs, row) -> read(rs.getString(1), AgentConfigRevision.class), limit);
    }

    @Override
    public void saveRelease(AgentConfigRelease release) {
        if (release == null) throw new IllegalArgumentException("Release is required");
        transactions.executeWithoutResult(status -> {
            AgentConfigRevision revision = findRevision(release.revisionId())
                    .orElseThrow(() -> new IllegalArgumentException("Release revision does not exist"));
            if (!revision.contentHash().equals(release.contentHash())) {
                throw new IllegalArgumentException("Release hash does not match its revision");
            }
            jdbc.update("""
                    INSERT INTO agent_config_release(release_id, revision_id, content_hash, evaluation_id,
                      baseline_release_id, gate_decision, source_commit, release_notes, created_by, created_at)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                    ON CONFLICT (release_id) DO NOTHING
                    """, release.releaseId(), release.revisionId(), release.contentHash(), release.evaluationId(),
                    release.baselineReleaseId(), json(release.gateDecision()), release.sourceCommit(),
                    release.releaseNotes(), release.createdBy(), timestamp(release.createdAt()));
            AgentConfigRelease saved = findRelease(release.releaseId()).orElseThrow();
            if (!saved.equals(release)) throw new IllegalStateException("Configuration release is immutable");
        });
    }

    @Override
    public Optional<AgentConfigRelease> findRelease(String releaseId) {
        return jdbc.query("""
                SELECT release_id, revision_id, content_hash, evaluation_id, baseline_release_id,
                  gate_decision::text AS gate_decision, source_commit, release_notes, created_by, created_at
                FROM agent_config_release WHERE release_id = ?
                """, rs -> rs.next() ? Optional.of(new AgentConfigRelease(rs.getString("release_id"),
                rs.getString("revision_id"), rs.getString("content_hash"), rs.getString("evaluation_id"),
                rs.getString("baseline_release_id"), readTree(rs.getString("gate_decision")),
                rs.getString("source_commit"), rs.getString("release_notes"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant())) : Optional.empty(), releaseId);
    }

    @Override
    public List<AgentConfigRelease> listReleases(int limit) {
        requireLimit(limit);
        return jdbc.query("SELECT release_id FROM agent_config_release ORDER BY created_at DESC, release_id LIMIT ?",
                        (rs, row) -> rs.getString(1), limit)
                .stream().map(id -> findRelease(id).orElseThrow()).toList();
    }

    @Override
    public Optional<ActiveConfigAssignment> assignment(String environment, String agentId) {
        return jdbc.query("""
                SELECT environment, agent_id, active_release_id, candidate_release_id, candidate_percent,
                  assignment_version, updated_at FROM active_config_assignment WHERE environment = ? AND agent_id = ?
                """, rs -> rs.next() ? Optional.of(new ActiveConfigAssignment(rs.getString("environment"),
                rs.getString("agent_id"), rs.getString("active_release_id"), rs.getString("candidate_release_id"),
                rs.getInt("candidate_percent"), rs.getLong("assignment_version"),
                rs.getTimestamp("updated_at").toInstant())) : Optional.empty(), environment, agentId);
    }

    @Override
    public boolean assign(String environment, String agentId, String activeReleaseId, String candidateReleaseId,
                          int candidatePercent, long expectedVersion, ReleaseAction action, String actor,
                          String reason, Instant at) {
        new ActiveConfigAssignment(environment, agentId, activeReleaseId, candidateReleaseId, candidatePercent,
                Math.max(1, expectedVersion + 1), at);
        if (expectedVersion < 0 || action == null || actor == null || actor.isBlank() || actor.length() > 120
                || reason == null || reason.isBlank() || reason.length() > 1000) {
            throw new IllegalArgumentException("Assignment audit data is invalid");
        }
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int changed;
            if (expectedVersion == 0) {
                changed = jdbc.update("""
                        INSERT INTO active_config_assignment(environment, agent_id, active_release_id,
                          candidate_release_id, candidate_percent, assignment_version, updated_at)
                        VALUES (?, ?, ?, ?, ?, 1, ?)
                        ON CONFLICT (environment, agent_id) DO NOTHING
                        """, environment, agentId, activeReleaseId, candidateReleaseId, candidatePercent, timestamp(at));
            } else {
                changed = jdbc.update("""
                        UPDATE active_config_assignment SET active_release_id = ?, candidate_release_id = ?,
                          candidate_percent = ?, assignment_version = assignment_version + 1, updated_at = ?
                        WHERE environment = ? AND agent_id = ? AND assignment_version = ?
                        """, activeReleaseId, candidateReleaseId, candidatePercent, timestamp(at), environment, agentId,
                        expectedVersion);
            }
            if (changed == 0) return false;
            String eventRelease = candidateReleaseId == null ? activeReleaseId : candidateReleaseId;
            jdbc.update("""
                    INSERT INTO agent_config_release_event(environment, agent_id, action, release_id, actor, reason, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, environment, agentId, action.name(), eventRelease, actor, reason, timestamp(at));
            return true;
        }));
    }

    @Override
    public List<ReleaseEvent> events(String environment, String agentId, int limit) {
        requireLimit(limit);
        return jdbc.query("""
                SELECT event_id, environment, agent_id, action, release_id, actor, reason, occurred_at
                FROM agent_config_release_event WHERE environment = ? AND agent_id = ?
                ORDER BY event_id DESC LIMIT ?
                """, (rs, row) -> new ReleaseEvent(rs.getLong("event_id"), rs.getString("environment"),
                rs.getString("agent_id"), ReleaseAction.valueOf(rs.getString("action")), rs.getString("release_id"),
                rs.getString("actor"), rs.getString("reason"), rs.getTimestamp("occurred_at").toInstant()),
                environment, agentId, limit);
    }

    @Override
    public void saveGateAttempt(ConfigurationGateAttempt attempt) {
        if (attempt == null) throw new IllegalArgumentException("Gate attempt is required");
        jdbc.update("""
                INSERT INTO agent_config_gate_attempt(revision_id, evaluation_id, baseline_release_id,
                  decision, actor, occurred_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?)
                """, attempt.revisionId(), attempt.evaluationId(), attempt.baselineReleaseId(),
                json(attempt.decision()), attempt.actor(), timestamp(attempt.occurredAt()));
    }

    @Override
    public List<ConfigurationGateAttempt> gateAttempts(int limit) {
        requireLimit(limit);
        return jdbc.query("""
                SELECT attempt_id, revision_id, evaluation_id, baseline_release_id, decision::text AS decision,
                       actor, occurred_at
                FROM agent_config_gate_attempt ORDER BY attempt_id DESC LIMIT ?
                """, (rs, row) -> new ConfigurationGateAttempt(rs.getLong("attempt_id"),
                rs.getString("revision_id"), rs.getString("evaluation_id"), rs.getString("baseline_release_id"),
                readTree(rs.getString("decision")), rs.getString("actor"),
                rs.getTimestamp("occurred_at").toInstant()), limit);
    }

    @Override
    public void approveRelease(ReleaseApproval approval) {
        if (approval == null) throw new IllegalArgumentException("Release approval is required");
        jdbc.update("""
                INSERT INTO agent_config_release_approval(release_id, actor, reason, approved_at)
                VALUES (?, ?, ?, ?) ON CONFLICT (release_id) DO NOTHING
                """, approval.releaseId(), approval.actor(), approval.reason(), timestamp(approval.approvedAt()));
        ReleaseApproval saved = jdbc.query("""
                SELECT release_id, actor, reason, approved_at FROM agent_config_release_approval WHERE release_id = ?
                """, rs -> rs.next() ? new ReleaseApproval(rs.getString("release_id"), rs.getString("actor"),
                rs.getString("reason"), rs.getTimestamp("approved_at").toInstant()) : null, approval.releaseId());
        if (!approval.equals(saved)) throw new IllegalStateException("Release approval is immutable");
    }

    @Override
    public boolean isApproved(String releaseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM agent_config_release_approval WHERE release_id = ?)",
                Boolean.class, releaseId));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Unable to serialize config record"); }
    }

    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Unable to read config record"); }
    }

    private com.fasterxml.jackson.databind.JsonNode readTree(String value) {
        try { return mapper.readTree(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Unable to read config record"); }
    }

    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("Page size must be between 1 and 500");
    }
}
