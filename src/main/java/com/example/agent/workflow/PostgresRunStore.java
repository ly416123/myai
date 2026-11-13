package com.example.agent.workflow;

import com.example.agent.collaboration.ChildTaskRecord;
import com.example.agent.collaboration.TaskStatus;
import com.example.agent.model.Message;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** PostgreSQL-backed RunStore with row-level leases and optimistic checkpoint versions. */
public final class PostgresRunStore implements RunStore, ToolExecutionAuditStore {
    private static final String SELECT_RUN = """
            SELECT c.payload::text AS payload, r.version, r.fencing_token, r.lease_owner, r.lease_expires_at,
                   r.cancel_requested, r.updated_at, r.model_calls, r.tool_calls
            FROM agent_run r JOIN run_checkpoint c ON c.run_id = r.run_id
            """;
    private static final int MAX_RUN_LIST_LIMIT = 1000;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public PostgresRunStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.mapper = java.util.Objects.requireNonNull(mapper);
        var dataSource = java.util.Objects.requireNonNull(jdbc.getDataSource(), "JdbcTemplate must have a DataSource");
        this.transactions = new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
    }

    @Override
    public void save(RunRecord run) {
        synchronized (run) {
            String payload = json(run);
            transactions.executeWithoutResult(status -> {
                jdbc.update("""
                                INSERT INTO agent_run(run_id, run_type, status, user_input, final_answer, failure_category,
                                  deadline_at, created_at, updated_at, model_calls, tool_calls, resumed, prompt_tokens,
                                  completion_tokens, token_usage_known, cancel_requested, version, fencing_token, lease_owner, lease_expires_at)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """, run.runId(), run.runType().name(), run.status().name(), run.userInput(), run.finalAnswer(),
                        name(run.failureCategory()), timestamp(run.deadlineAt()), timestamp(run.createdAt()),
                        timestamp(run.updatedAt()), run.modelCalls(), run.toolCalls(), run.resumed(), run.promptTokens(),
                        run.completionTokens(), run.tokenUsageKnown(), run.cancellationRequested(), run.version(), run.fencingToken(),
                        run.leaseOwner(), timestamp(run.leaseExpiresAt()));
                jdbc.update("INSERT INTO run_checkpoint(run_id, version, payload, saved_at) VALUES (?, ?, ?::jsonb, ?)",
                        run.runId(), run.version(), payload, timestamp(run.updatedAt()));
                persistProjection(run, run.updatedAt());
            });
        }
    }

    @Override
    public Optional<RunRecord> find(String runId) {
        try {
            List<RunRecord> rows = jdbc.query(SELECT_RUN + " WHERE r.run_id = ?", (rs, row) -> readRun(rs), runId);
            if (rows.isEmpty()) return Optional.empty();
            return Optional.of(hydrateChildTasks(rows).getFirst());
        } catch (DataAccessResourceFailureException | TransientDataAccessException
                 | RecoverableDataAccessException unavailable) {
            throw new RunStoreUnavailableException(unavailable);
        }
    }

    @Override
    public List<RunRecord> list() {
        return hydrateChildTasks(jdbc.query(SELECT_RUN + " ORDER BY r.created_at, r.run_id",
                (rs, row) -> readRun(rs)));
    }

    @Override
    public List<ToolExecutionAudit> findToolAudit(String runId) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("Run ID is required");
        return jdbc.query("""
                SELECT audit_id, run_id, step_id, tool_call_id, task_id, worker_id, tool_name,
                       status, failure_category, occurred_at
                FROM tool_execution_audit WHERE run_id = ? ORDER BY occurred_at, audit_id
                """, (rs, row) -> new ToolExecutionAudit(rs.getLong("audit_id"), rs.getString("run_id"),
                rs.getString("step_id"), rs.getString("tool_call_id"), rs.getString("task_id"),
                rs.getString("worker_id"), rs.getString("tool_name"), RunStepStatus.valueOf(rs.getString("status")),
                enumValue(FailureCategory.class, rs.getString("failure_category")),
                instant(rs.getTimestamp("occurred_at"))), runId);
    }

    @Override
    public List<RunRecord> listRecent(int limit) {
        if (limit < 1 || limit > MAX_RUN_LIST_LIMIT) throw new IllegalArgumentException("Invalid Run list limit");
        return hydrateChildTasks(jdbc.query(SELECT_RUN + " ORDER BY r.created_at DESC, r.run_id DESC LIMIT ?",
                (rs, row) -> readRun(rs), limit));
    }

    @Override
    public List<RunRecord> findRecoverable(Instant now, int limit) {
        if (now == null || limit < 1 || limit > 10_000)
            throw new IllegalArgumentException("Invalid recovery batch request");
        List<String> runIds = jdbc.queryForList("""
                        SELECT run_id FROM agent_run
                        WHERE status IN ('CREATED', 'RUNNING')
                          AND (lease_expires_at IS NULL OR lease_expires_at <= ?)
                        ORDER BY created_at, run_id
                        LIMIT ?
                        """, String.class, timestamp(now), limit);
        return runIds.stream().map(this::find).flatMap(Optional::stream).toList();
    }

    @Override
    public List<RunRecord> findActiveCollaborativeRuns(Instant now, int limit) {
        if (now == null || limit < 1 || limit > 10_000)
            throw new IllegalArgumentException("Invalid child task scan request");
        List<RunRecord> runs = jdbc.query(SELECT_RUN + " WHERE r.run_type = 'COLLABORATIVE' "
                        + "AND r.status = 'RUNNING' AND r.lease_expires_at > ? "
                        + "AND r.deadline_at > ? AND r.cancel_requested = false "
                        + "AND EXISTS (SELECT 1 FROM child_task t WHERE t.run_id = r.run_id "
                        + "AND t.status IN ('PENDING', 'IN_PROGRESS')) "
                        + "ORDER BY r.created_at, r.run_id LIMIT ?",
                (rs, row) -> readRun(rs), timestamp(now), timestamp(now), limit);
        return hydrateChildTasks(runs);
    }

    @Override
    public RunQueueSnapshot queueSnapshot(Instant now) {
        if (now == null) throw new IllegalArgumentException("Snapshot time is required");
        return jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE status IN ('CREATED', 'RUNNING')
                           AND (lease_expires_at IS NULL OR lease_expires_at <= ?)) AS recoverable_runs,
                       COUNT(*) FILTER (WHERE status IN ('CREATED', 'RUNNING') AND lease_expires_at IS NOT NULL
                           AND lease_expires_at <= ?) AS expired_leases,
                       COUNT(*) FILTER (WHERE status = 'FAILED' AND updated_at >= ? AND updated_at <= ?)
                           AS recent_failed_runs,
                       MIN(created_at) FILTER (WHERE status IN ('CREATED', 'RUNNING')
                           AND (lease_expires_at IS NULL OR lease_expires_at <= ?)) AS oldest_created_at
                FROM agent_run
                """, (rs, row) -> {
            long recoverable = rs.getLong("recoverable_runs");
            long expired = rs.getLong("expired_leases");
            long recentFailed = rs.getLong("recent_failed_runs");
            Instant oldest = instant(rs.getTimestamp("oldest_created_at"));
            Duration oldestWait = oldest == null ? Duration.ZERO : Duration.between(oldest, now);
            if (oldestWait.isNegative()) oldestWait = Duration.ZERO;
            return new RunQueueSnapshot(recoverable, expired, recentFailed, oldestWait);
        }, timestamp(now), timestamp(now), timestamp(now.minus(Duration.ofMinutes(5))), timestamp(now), timestamp(now));
    }

    @Override
    public int purgeTerminalRunsBefore(Instant cutoff, int limit) {
        if (cutoff == null || limit < 1 || limit > 10_000)
            throw new IllegalArgumentException("Invalid retention batch request");
        return transactions.execute(status -> {
            int deleted = jdbc.update("""
                    WITH expired AS (
                      SELECT run_id FROM agent_run
                      WHERE status IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED') AND updated_at < ?
                      ORDER BY updated_at, run_id
                      LIMIT ? FOR UPDATE SKIP LOCKED
                    )
                    DELETE FROM agent_run r USING expired e WHERE r.run_id = e.run_id
                    """, timestamp(cutoff), limit);
            jdbc.update("INSERT INTO run_retention_audit(cutoff_at, deleted_runs) VALUES (?, ?)",
                    timestamp(cutoff), deleted);
            return deleted;
        });
    }

    @Override
    public Optional<LeaseToken> claim(String runId, String ownerId, Instant now, Duration leaseDuration) {
        requireLease(ownerId, leaseDuration);
        return jdbc.query("""
                        UPDATE agent_run SET lease_owner = ?, lease_expires_at = ?, fencing_token = fencing_token + 1
                        WHERE run_id = ? AND status IN ('CREATED', 'RUNNING')
                          AND (lease_expires_at IS NULL OR lease_expires_at <= ?)
                        RETURNING fencing_token
                        """, (rs, row) -> new LeaseToken(runId, ownerId, rs.getLong(1)),
                ownerId, timestamp(now.plus(leaseDuration)), runId, timestamp(now))
                .stream().findFirst();
    }

    @Override
    public boolean saveFenced(RunRecord run, LeaseToken token, Instant now) {
        if (!run.runId().equals(token.runId())) return false;
        synchronized (run) {
            long expectedVersion = run.version();
            long nextVersion = expectedVersion + 1;
            run.setVersion(nextVersion);
            String payload = json(run);
            try {
                Boolean saved = transactions.execute(status -> {
                    int updated = jdbc.update("""
                                    UPDATE agent_run SET status = ?, user_input = ?, final_answer = ?, failure_category = ?,
                                      deadline_at = ?, updated_at = ?, model_calls = GREATEST(model_calls, ?),
                                      tool_calls = GREATEST(tool_calls, ?), resumed = ?,
                                      prompt_tokens = ?, completion_tokens = ?, token_usage_known = ?, version = ?
                                    WHERE run_id = ? AND version = ? AND fencing_token = ? AND lease_owner = ?
                                      AND lease_expires_at > ? AND status IN ('CREATED', 'RUNNING')
                                    """, run.status().name(), run.userInput(), run.finalAnswer(), name(run.failureCategory()),
                            timestamp(run.deadlineAt()), timestamp(run.updatedAt()), run.modelCalls(), run.toolCalls(),
                            run.resumed(), run.promptTokens(), run.completionTokens(), run.tokenUsageKnown(), nextVersion,
                            run.runId(), expectedVersion, token.fencingToken(), token.ownerId(), timestamp(now));
                    if (updated != 1) return false;
                    jdbc.update("UPDATE run_checkpoint SET version = ?, payload = ?::jsonb, saved_at = ? WHERE run_id = ?",
                            nextVersion, payload, timestamp(run.updatedAt()), run.runId());
                    persistProjection(run, now);
                    return true;
                });
                if (!Boolean.TRUE.equals(saved)) run.setVersion(expectedVersion);
                return Boolean.TRUE.equals(saved);
            } catch (RuntimeException failure) {
                run.setVersion(expectedVersion);
                throw failure;
            }
        }
    }

    @Override
    public boolean renew(LeaseToken token, Instant now, Duration leaseDuration) {
        requireLease(token.ownerId(), leaseDuration);
        return jdbc.update("""
                        UPDATE agent_run SET lease_expires_at = ?
                        WHERE run_id = ? AND fencing_token = ? AND lease_owner = ? AND lease_expires_at > ?
                          AND status IN ('CREATED', 'RUNNING')
                        """, timestamp(now.plus(leaseDuration)), token.runId(), token.fencingToken(),
                token.ownerId(), timestamp(now)) == 1;
    }

    @Override
    public boolean requestCancellation(String runId, Instant requestedAt) {
        return jdbc.update("""
                        UPDATE agent_run SET cancel_requested = true, updated_at = ?
                        WHERE run_id = ? AND status IN ('CREATED', 'RUNNING')
                        """, timestamp(requestedAt), runId) == 1;
    }

    @Override
    public Optional<TaskLeaseToken> claimChildTask(LeaseToken parentLease, String taskId, String ownerId,
                                                   Instant now, Duration leaseDuration) {
        requireLease(ownerId, leaseDuration);
        if (taskId == null || taskId.isBlank() || taskId.length() > 80) {
            throw new IllegalArgumentException("Invalid child task lease request");
        }
        return jdbc.query("""
                        UPDATE child_task t
                        SET task_lease_owner = ?, task_lease_expires_at = ?,
                            task_fencing_token = task_fencing_token + 1,
                            parent_fencing_token = r.fencing_token
                        FROM agent_run r
                        WHERE t.run_id = ? AND t.task_id = ? AND t.status IN ('PENDING', 'IN_PROGRESS')
                          AND r.run_id = t.run_id AND r.status IN ('CREATED', 'RUNNING')
                          AND r.lease_owner = ? AND r.fencing_token = ? AND r.lease_expires_at > ?
                          AND r.deadline_at > ? AND r.cancel_requested = false
                          AND (t.task_lease_expires_at IS NULL OR t.task_lease_expires_at <= ?
                               OR t.parent_fencing_token <> r.fencing_token)
                        RETURNING t.task_fencing_token
                        """, (rs, row) -> new TaskLeaseToken(parentLease.runId(), taskId, ownerId, rs.getLong(1)),
                ownerId, timestamp(now.plus(leaseDuration)), parentLease.runId(), taskId,
                parentLease.ownerId(), parentLease.fencingToken(), timestamp(now), timestamp(now),
                timestamp(now)).stream().findFirst();
    }

    @Override
    public boolean isChildTaskLeaseCurrent(TaskLeaseToken taskLease, LeaseToken parentLease, Instant now) {
        if (!taskLease.runId().equals(parentLease.runId())) return false;
        Boolean current = jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM child_task t JOIN agent_run r ON r.run_id = t.run_id
                  WHERE t.run_id = ? AND t.task_id = ? AND t.task_lease_owner = ?
                    AND t.task_fencing_token = ? AND t.task_lease_expires_at > ?
                    AND r.status IN ('CREATED', 'RUNNING') AND r.lease_owner = ?
                    AND r.fencing_token = ? AND t.parent_fencing_token = r.fencing_token
                    AND r.lease_expires_at > ?
                )
                """, Boolean.class, taskLease.runId(), taskLease.taskId(), taskLease.ownerId(),
                taskLease.fencingToken(), timestamp(now), parentLease.ownerId(), parentLease.fencingToken(),
                timestamp(now));
        return Boolean.TRUE.equals(current);
    }

    @Override
    public boolean renewChildTaskLease(LeaseToken parentLease, TaskLeaseToken taskLease,
                                      Instant now, Duration leaseDuration) {
        if (parentLease == null || taskLease == null || !parentLease.runId().equals(taskLease.runId())
                || now == null || leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative())
            return false;
        return jdbc.update("""
                UPDATE child_task t SET task_lease_expires_at = ?
                FROM agent_run r
                WHERE t.run_id = ? AND t.task_id = ? AND t.task_lease_owner = ?
                  AND t.task_fencing_token = ? AND t.parent_fencing_token = r.fencing_token
                  AND t.task_lease_expires_at > ? AND r.run_id = t.run_id
                  AND r.status IN ('CREATED', 'RUNNING') AND r.lease_owner = ?
                  AND r.fencing_token = ? AND r.lease_expires_at > ?
                """, timestamp(now.plus(leaseDuration)), taskLease.runId(), taskLease.taskId(),
                taskLease.ownerId(), taskLease.fencingToken(), timestamp(now), parentLease.ownerId(),
                parentLease.fencingToken(), timestamp(now)) == 1;
    }

    @Override
    public boolean saveChildTaskFenced(LeaseToken parentLease, TaskLeaseToken taskLease,
                                       ChildTaskRecord task, Instant now) {
        if (parentLease == null || task == null || now == null || task.status() == null
                || taskLease != null && (!parentLease.runId().equals(taskLease.runId())
                || !task.taskId().equals(taskLease.taskId()))) {
            throw new IllegalArgumentException("Invalid child task checkpoint");
        }
        boolean terminal = isTerminal(task.status());
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int updated;
            if (taskLease == null) {
                updated = jdbc.update("""
                        UPDATE child_task t SET worker_id = ?, status = ?, payload = ?::jsonb,
                          task_lease_owner = CASE WHEN ? THEN NULL ELSE t.task_lease_owner END,
                          task_lease_expires_at = CASE WHEN ? THEN NULL ELSE t.task_lease_expires_at END
                        FROM agent_run r
                        WHERE t.run_id = ? AND t.task_id = ? AND t.status IN ('PENDING', 'IN_PROGRESS')
                          AND ((t.task_lease_owner IS NULL OR t.task_lease_expires_at <= ?
                                OR t.parent_fencing_token <> r.fencing_token)
                               OR (r.status IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED') AND ?))
                          AND r.run_id = t.run_id
                          AND (r.status IN ('CREATED', 'RUNNING') OR
                               (r.status IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED') AND ?))
                          AND r.lease_owner = ? AND r.fencing_token = ? AND r.lease_expires_at > ?
                        """, task.workerId(), task.status().name(), json(task), terminal, terminal,
                        parentLease.runId(), task.taskId(), timestamp(now), terminal, terminal, parentLease.ownerId(),
                        parentLease.fencingToken(), timestamp(now));
            } else {
                updated = jdbc.update("""
                        UPDATE child_task t SET worker_id = ?, status = ?, payload = ?::jsonb,
                          task_lease_owner = CASE WHEN ? THEN NULL ELSE t.task_lease_owner END,
                          task_lease_expires_at = CASE WHEN ? THEN NULL ELSE t.task_lease_expires_at END
                        FROM agent_run r
                        WHERE t.run_id = ? AND t.task_id = ? AND t.status IN ('PENDING', 'IN_PROGRESS')
                          AND t.task_lease_owner = ? AND t.task_fencing_token = ?
                          AND t.parent_fencing_token = r.fencing_token AND t.task_lease_expires_at > ?
                          AND r.run_id = t.run_id AND r.status IN ('CREATED', 'RUNNING')
                          AND r.lease_owner = ? AND r.fencing_token = ? AND r.lease_expires_at > ?
                        """, task.workerId(), task.status().name(), json(task), terminal, terminal,
                        taskLease.runId(), taskLease.taskId(), taskLease.ownerId(), taskLease.fencingToken(), timestamp(now),
                        parentLease.ownerId(), parentLease.fencingToken(), timestamp(now));
            }
            if (updated != 1) return false;
            Map<String, RunStepStatus> previousStatuses = new LinkedHashMap<>();
            jdbc.query("SELECT step_id, status FROM run_step WHERE run_id = ? AND payload->>'taskId' = ?",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> previousStatuses.put(
                            rs.getString("step_id"), RunStepStatus.valueOf(rs.getString("status"))),
                    parentLease.runId(), task.taskId());
            for (RunStep step : task.steps()) {
                if (step.kind() == StepKind.TOOL) {
                    appendToolAuditIfChanged(parentLease.runId(), step, previousStatuses.get(step.stepId()), now);
                }
            }
            jdbc.update("DELETE FROM run_step WHERE run_id = ? AND step_id IN "
                    + "(SELECT step_id FROM run_step WHERE run_id = ? AND payload->>'taskId' = ?)",
                    parentLease.runId(), parentLease.runId(), task.taskId());
            for (RunStep step : task.steps()) {
                jdbc.update("""
                        INSERT INTO run_step(run_id, step_id, kind, status, started_at, finished_at, failure_category, payload)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                        ON CONFLICT (run_id, step_id) DO UPDATE SET status = EXCLUDED.status,
                          finished_at = EXCLUDED.finished_at, failure_category = EXCLUDED.failure_category,
                          payload = EXCLUDED.payload
                        """, parentLease.runId(), step.stepId(), step.kind().name(), step.status().name(),
                        timestamp(step.startedAt()), timestamp(step.completedAt()), name(step.failureCategory()), json(step));
            }
            return true;
        }));
    }

    @Override
    public boolean reserveBudget(LeaseToken parentLease, TaskLeaseToken taskLease, BudgetKind kind,
                                 Collection<String> reservationIds, int limit, Instant now) {
        validateBudgetRequest(parentLease, kind, reservationIds, limit, now);
        List<String> ids = List.copyOf(new HashSet<>(reservationIds));
        if (ids.size() != reservationIds.size()) throw new IllegalArgumentException("Duplicate budget reservation ID");
        return Boolean.TRUE.equals(transactions.execute(status -> {
            List<int[]> counters = jdbc.query("""
                    SELECT model_calls, tool_calls FROM agent_run
                    WHERE run_id = ? AND status IN ('CREATED', 'RUNNING') AND lease_owner = ?
                      AND fencing_token = ? AND lease_expires_at > ? AND deadline_at > ?
                      AND cancel_requested = false FOR UPDATE
                    """, (rs, row) -> new int[]{rs.getInt(1), rs.getInt(2)}, parentLease.runId(),
                    parentLease.ownerId(), parentLease.fencingToken(), timestamp(now), timestamp(now));
            if (counters.isEmpty()) return false;
            if (taskLease != null) {
                Boolean currentTaskLease = jdbc.queryForObject("""
                        SELECT EXISTS (
                          SELECT 1 FROM child_task t JOIN agent_run r ON r.run_id = t.run_id
                          WHERE t.run_id = ? AND t.task_id = ? AND t.task_lease_owner = ?
                            AND t.task_fencing_token = ? AND t.task_lease_expires_at > ?
                            AND t.parent_fencing_token = r.fencing_token
                            AND r.lease_owner = ? AND r.fencing_token = ? AND r.lease_expires_at > ?
                            AND r.status IN ('CREATED', 'RUNNING')
                        )
                        """, Boolean.class, taskLease.runId(), taskLease.taskId(), taskLease.ownerId(),
                        taskLease.fencingToken(), timestamp(now), parentLease.ownerId(),
                        parentLease.fencingToken(), timestamp(now));
                if (!Boolean.TRUE.equals(currentTaskLease)) return false;
            }

            List<String> newIds = new java.util.ArrayList<>();
            for (String id : ids) {
                List<String> existing = jdbc.queryForList("""
                        SELECT reservation_kind FROM run_budget_reservation
                        WHERE run_id = ? AND reservation_id = ?
                        """, String.class, parentLease.runId(), id);
                if (existing.isEmpty()) newIds.add(id);
                else if (!existing.getFirst().equals(kind.name())) return false;
            }
            if (newIds.isEmpty()) return true;

            int current = counters.getFirst()[kind == BudgetKind.MODEL_CALL ? 0 : 1];
            if (current + newIds.size() > limit) return false;
            String counterColumn = kind == BudgetKind.MODEL_CALL ? "model_calls" : "tool_calls";
            int updated = jdbc.update("UPDATE agent_run SET " + counterColumn + " = " + counterColumn + " + ? "
                            + "WHERE run_id = ? AND lease_owner = ? AND fencing_token = ? AND lease_expires_at > ? "
                            + "AND " + counterColumn + " + ? <= ?",
                    newIds.size(), parentLease.runId(), parentLease.ownerId(), parentLease.fencingToken(),
                    timestamp(now), newIds.size(), limit);
            if (updated != 1) return false;
            jdbc.batchUpdate("""
                    INSERT INTO run_budget_reservation(run_id, reservation_id, reservation_kind, amount, reserved_at)
                    VALUES (?, ?, ?, 1, ?)
                    """, newIds.stream().map(id -> new Object[]{parentLease.runId(), id, kind.name(), timestamp(now)}).toList());
            return true;
        }));
    }

    private RunRecord readRun(ResultSet rs) throws SQLException {
        try {
            RunRecord run = mapper.readValue(rs.getString("payload"), RunRecord.class);
            run.setVersion(rs.getLong("version"));
            run.setFencingToken(rs.getLong("fencing_token"));
            run.setLeaseOwner(rs.getString("lease_owner"));
            run.setLeaseExpiresAt(instant(rs.getTimestamp("lease_expires_at")));
            run.setCancellationRequested(rs.getBoolean("cancel_requested"));
            run.setUpdatedAt(instant(rs.getTimestamp("updated_at")));
            run.setModelCalls(rs.getInt("model_calls"));
            run.setToolCalls(rs.getInt("tool_calls"));
            return run;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to deserialize run checkpoint", e);
        }
    }

    private List<RunRecord> hydrateChildTasks(List<RunRecord> runs) {
        if (runs.isEmpty()) return runs;
        List<String> runIds = runs.stream().map(RunRecord::runId).toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(runIds.size(), "?"));
        Map<String, List<ChildTaskRecord>> tasksByRun = new LinkedHashMap<>();
        jdbc.query("SELECT run_id, payload::text AS payload FROM child_task WHERE run_id IN (" + placeholders
                        + ") ORDER BY run_id, task_id", (rs) -> {
            try {
                ChildTaskRecord task = mapper.readValue(rs.getString("payload"), ChildTaskRecord.class);
                tasksByRun.computeIfAbsent(rs.getString("run_id"), ignored -> new java.util.ArrayList<>()).add(task);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Unable to deserialize child task checkpoint");
            }
        }, runIds.toArray());
        for (RunRecord run : runs) {
            List<ChildTaskRecord> tasks = tasksByRun.get(run.runId());
            if (tasks != null) {
                run.setChildTasks(tasks);
                List<RunStep> steps = new java.util.ArrayList<>(run.steps().stream()
                        .filter(step -> step.taskId() == null).toList());
                tasks.forEach(task -> steps.addAll(task.steps()));
                run.setSteps(steps);
            }
        }
        return runs;
    }

    private void persistProjection(RunRecord run, Instant auditAt) {
        for (int i = 0; i < run.messages().size(); i++) {
            Message message = run.messages().get(i);
            jdbc.update("""
                            INSERT INTO run_message(run_id, ordinal, role, payload) VALUES (?, ?, ?, ?::jsonb)
                            ON CONFLICT (run_id, ordinal) DO UPDATE SET role = EXCLUDED.role, payload = EXCLUDED.payload
                            """, run.runId(), i, message.role().name(), json(message));
        }
        for (RunStep step : run.steps()) {
            // Child task steps are written only by saveChildTaskFenced under that task's lease.
            if (step.taskId() != null) continue;
            RunStepStatus previousStatus = jdbc.query("SELECT status FROM run_step WHERE run_id = ? AND step_id = ?",
                    rs -> rs.next() ? RunStepStatus.valueOf(rs.getString(1)) : null, run.runId(), step.stepId());
            if (step.kind() == StepKind.TOOL) appendToolAuditIfChanged(run.runId(), step, previousStatus, auditAt);
            jdbc.update("""
                            INSERT INTO run_step(run_id, step_id, kind, status, started_at, finished_at, failure_category, payload)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                            ON CONFLICT (run_id, step_id) DO UPDATE SET status = EXCLUDED.status,
                              finished_at = EXCLUDED.finished_at, failure_category = EXCLUDED.failure_category, payload = EXCLUDED.payload
                            """, run.runId(), step.stepId(), step.kind().name(), step.status().name(),
                    timestamp(step.startedAt()), timestamp(step.completedAt()), name(step.failureCategory()), json(step));
        }
        for (ChildTaskRecord task : run.childTasks()) {
            jdbc.update("""
                            INSERT INTO child_task(run_id, task_id, worker_id, status, payload) VALUES (?, ?, ?, ?, ?::jsonb)
                            ON CONFLICT (run_id, task_id) DO NOTHING
                            """, run.runId(), task.taskId(), task.workerId(), task.status().name(), json(task));
        }
        for (var reservation : run.budgetReservations().entrySet()) {
            jdbc.update("""
                    INSERT INTO run_budget_reservation(run_id, reservation_id, reservation_kind, amount, reserved_at)
                    VALUES (?, ?, ?, 1, ?) ON CONFLICT (run_id, reservation_id) DO NOTHING
                    """, run.runId(), reservation.getKey(), reservation.getValue().name(), timestamp(run.updatedAt()));
        }
    }

    private void appendToolAuditIfChanged(String runId, RunStep step, RunStepStatus previousStatus, Instant now) {
        if (step.status() == previousStatus) return;
        jdbc.update("""
                INSERT INTO tool_execution_audit(run_id, step_id, tool_call_id, task_id, worker_id,
                  tool_name, status, failure_category, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, runId, step.stepId(), step.toolCallId(), step.taskId(), step.workerId(), step.toolName(),
                step.status().name(), name(step.failureCategory()), timestamp(now));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Unable to serialize run checkpoint", e); }
    }

    private static void requireLease(String ownerId, Duration duration) {
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 120 || duration == null
                || duration.isZero() || duration.isNegative()) throw new IllegalArgumentException("Invalid lease request");
    }

    private static void validateBudgetRequest(LeaseToken parentLease, BudgetKind kind,
                                              Collection<String> reservationIds, int limit, Instant now) {
        if (parentLease == null || kind == null || reservationIds == null || reservationIds.isEmpty()
                || reservationIds.size() > 1000 || limit < 0 || now == null
                || reservationIds.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 80))
            throw new IllegalArgumentException("Invalid Run budget reservation");
    }

    private static String name(Enum<?> value) { return value == null ? null : value.name(); }
    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
    private static boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.SUCCEEDED || status == TaskStatus.FAILED
                || status == TaskStatus.SKIPPED || status == TaskStatus.INDETERMINATE;
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
