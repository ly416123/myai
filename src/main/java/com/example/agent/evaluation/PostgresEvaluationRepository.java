package com.example.agent.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** PostgreSQL repository; conditional updates provide the single-executor and terminal-state fences. */
public final class PostgresEvaluationRepository implements EvaluationRepository {
    private static final TypeReference<Map<String, String>> VERSIONS = new TypeReference<>() { };
    private static final TypeReference<List<EvaluationScore>> SCORES = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public PostgresEvaluationRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.mapper = java.util.Objects.requireNonNull(mapper);
        var dataSource = java.util.Objects.requireNonNull(jdbc.getDataSource());
        this.transactions = new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
    }

    @Override public void create(EvaluationRunRecord run, List<String> caseIds) {
        if (run == null || run.status() != EvaluationRunStatus.CREATED || caseIds == null || caseIds.isEmpty()
                || caseIds.size() > 100 || caseIds.stream().anyMatch(id -> id == null || id.isBlank())
                || caseIds.stream().distinct().count() != caseIds.size())
            throw new IllegalArgumentException("Invalid evaluation creation request");
        transactions.executeWithoutResult(tx -> {
            var d = run.dataset(); var a = run.aggregate();
            jdbc.update("""
                    INSERT INTO evaluation_run(evaluation_id,status,dataset_id,dataset_version,dataset_hash,
                    dataset_format_version,git_commit,workflow_mode,provider,model,evaluator_versions,model_call_budget,
                    tool_call_budget,max_cases,created_at,deadline_at,total_cases,completed_cases,passed_cases,failed_cases,
                    model_calls,tool_calls,prompt_tokens,completion_tokens,estimated_cost_usd,failure_category)
                    VALUES(?,?,?,?,?,?, ?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    run.evaluationId(), run.status().name(), d.datasetId(), d.datasetVersion(), d.contentHash(),
                    d.formatVersion(), run.gitCommit(), run.workflowMode(), run.provider(), run.model(), json(run.evaluatorVersions()),
                    run.modelCallBudget(), run.toolCallBudget(), run.maxCases(), ts(run.createdAt()), ts(run.deadlineAt()),
                    caseIds.size(), a.completedCases(), a.passedCases(), a.failedCases(), a.modelCalls(), a.toolCalls(),
                    a.promptTokens(), a.completionTokens(), a.estimatedCostUsd(), run.failureCategory());
            caseIds.forEach(id -> jdbc.update("INSERT INTO evaluation_case(evaluation_id,case_id,status) VALUES (?,?,'PENDING')",
                    run.evaluationId(), id));
            jdbc.update("INSERT INTO evaluation_status_event(evaluation_id,status,occurred_at) VALUES (?, 'CREATED', ?)",
                    run.evaluationId(), ts(run.createdAt()));
        });
    }

    @Override public Optional<EvaluationLease> start(String id, String owner, Instant leaseExpiresAt, Instant at) {
        return transactions.execute(tx -> {
            var fences = jdbc.query("""
                    UPDATE evaluation_run SET status='RUNNING',started_at=?,execution_owner=?,
                      execution_fence=execution_fence+1,lease_expires_at=?
                    WHERE evaluation_id=? AND status='CREATED' RETURNING execution_fence
                    """, (rs, row) -> rs.getLong(1), ts(at), owner, ts(leaseExpiresAt), id);
            if (fences.isEmpty()) return Optional.empty();
            jdbc.update("INSERT INTO evaluation_status_event(evaluation_id,status,occurred_at) VALUES (?, 'RUNNING', ?)", id, ts(at));
            return Optional.of(new EvaluationLease(id, owner, fences.getFirst()));
        });
    }

    @Override public boolean renew(EvaluationLease lease, Instant at, Instant leaseExpiresAt) {
        return jdbc.update("UPDATE evaluation_run SET lease_expires_at=? WHERE evaluation_id=? AND status='RUNNING' "
                        + "AND execution_owner=? AND execution_fence=? AND lease_expires_at>?",
                ts(leaseExpiresAt), lease.evaluationId(), lease.owner(), lease.fence(), ts(at)) == 1;
    }

    @Override public boolean startCase(EvaluationLease lease, String caseId, Instant at) {
        return Boolean.TRUE.equals(transactions.execute(tx -> {
            if (!lockOwner(lease, at, true)) return false;
            return jdbc.update("UPDATE evaluation_case SET status='RUNNING',started_at=?,attempt=attempt+1 "
                    + "WHERE evaluation_id=? AND case_id=? AND status='PENDING'", ts(at), lease.evaluationId(), caseId) == 1;
        }));
    }

    @Override public boolean saveCase(EvaluationLease lease, EvaluationCaseRecord c, Instant at) {
        if (c == null || c.status() == EvaluationCaseStatus.PENDING || c.status() == EvaluationCaseStatus.RUNNING)
            throw new IllegalArgumentException("Only a terminal case result can be saved");
        return Boolean.TRUE.equals(transactions.execute(tx -> {
        if (!lockOwner(lease, at, true)) return false;
        return jdbc.update("""
                UPDATE evaluation_case SET status=?,run_id=?,failure_category=?,scores=?::jsonb,
                model_calls=?,tool_calls=?,token_usage_known=?,prompt_tokens=?,completion_tokens=?,duration_millis=?,
                started_at=COALESCE(started_at,?),completed_at=?,attempt=?
                WHERE evaluation_id=? AND case_id=? AND status IN ('PENDING','RUNNING')""",
                c.status().name(), c.runId(), c.failureCategory(), json(c.scores()), c.modelCalls(), c.toolCalls(),
                c.tokenUsageKnown(), c.promptTokens(), c.completionTokens(), c.durationMillis(), ts(c.startedAt()),
                ts(c.completedAt()), c.attempt(), c.evaluationId(), c.caseId()) == 1;
        }));
    }

    @Override public boolean finish(EvaluationLease lease, EvaluationRunStatus status, EvaluationAggregate a, Instant at, String failure) {
        if (status == null || status == EvaluationRunStatus.CREATED || status == EvaluationRunStatus.RUNNING)
            throw new IllegalArgumentException("Evaluation must finish in a terminal status");
        return Boolean.TRUE.equals(transactions.execute(tx -> {
        if (!lockOwner(lease, at, false)) return false;
        int updated = jdbc.update("""
                UPDATE evaluation_run SET status=?,completed_at=?,total_cases=?,completed_cases=?,passed_cases=?,
                failed_cases=?,model_calls=?,tool_calls=?,prompt_tokens=?,completion_tokens=?,estimated_cost_usd=?,failure_category=?,
                execution_owner=NULL,lease_expires_at=NULL
                WHERE evaluation_id=? AND status='RUNNING' AND execution_owner=? AND execution_fence=?""",
                status.name(), ts(at), a.totalCases(), a.completedCases(),
                a.passedCases(), a.failedCases(), a.modelCalls(), a.toolCalls(), a.promptTokens(), a.completionTokens(),
                a.estimatedCostUsd(), failure, lease.evaluationId(), lease.owner(), lease.fence());
        if (updated == 1) jdbc.update("INSERT INTO evaluation_status_event(evaluation_id,status,occurred_at,failure_category) VALUES (?,?,?,?)",
                lease.evaluationId(), status.name(), ts(at), failure);
        return updated == 1;
        }));
    }

    @Override public Optional<EvaluationSnapshot> find(String id) {
        var runs = jdbc.query("SELECT * FROM evaluation_run WHERE evaluation_id=?", (rs, row) -> readRun(rs), id);
        if (runs.isEmpty()) return Optional.empty();
        var cases = jdbc.query("SELECT * FROM evaluation_case WHERE evaluation_id=? ORDER BY case_id", (rs, row) -> readCase(rs), id);
        var events = jdbc.query("SELECT status,occurred_at,failure_category FROM evaluation_status_event WHERE evaluation_id=? ORDER BY event_id",
                (rs, row) -> new EvaluationStatusEvent(EvaluationRunStatus.valueOf(rs.getString(1)),
                        instant(rs.getTimestamp(2)), rs.getString(3)), id);
        var leaseExpiry = jdbc.queryForObject("SELECT lease_expires_at FROM evaluation_run WHERE evaluation_id=?",
                (rs, row) -> instant(rs.getTimestamp(1)), id);
        return Optional.of(new EvaluationSnapshot(runs.getFirst(), cases, events, leaseExpiry));
    }

    @Override public List<EvaluationRunRecord> list(int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("Invalid evaluation list limit");
        return jdbc.query("SELECT * FROM evaluation_run ORDER BY created_at DESC,evaluation_id DESC LIMIT ?",
                (rs, row) -> readRun(rs), limit);
    }

    @Override public int recoverExpired(String id, Instant at) {
        return transactions.execute(tx -> {
        var eligible = jdbc.query("SELECT execution_owner,execution_fence FROM evaluation_run WHERE evaluation_id=? "
                        + "AND status='RUNNING' AND deadline_at<? AND lease_expires_at<? FOR UPDATE",
                (rs, row) -> Map.entry(rs.getString(1), rs.getLong(2)), id, ts(at), ts(at));
        if (eligible.isEmpty()) return 0;
        var evaluatorVersions = jdbc.queryForObject("SELECT evaluator_versions::text FROM evaluation_run WHERE evaluation_id=?",
                String.class, id);
        var unknownScores = parse(evaluatorVersions, VERSIONS).entrySet().stream()
                .map(e -> EvaluationScore.of(e.getKey(), e.getValue(), EvaluationOutcome.UNKNOWN,
                        "INTERRUPTED_OUTCOME_UNKNOWN")).toList();
        var skippedScores = parse(evaluatorVersions, VERSIONS).entrySet().stream()
                .map(e -> EvaluationScore.of(e.getKey(), e.getValue(), EvaluationOutcome.UNKNOWN,
                        "NOT_STARTED_AFTER_INTERRUPTION")).toList();
        int unknown = jdbc.update("""
                UPDATE evaluation_case SET status='INDETERMINATE',failure_category='INTERRUPTED_OUTCOME_UNKNOWN',
                scores=?::jsonb,completed_at=? WHERE evaluation_id=? AND status='RUNNING'""",
                json(unknownScores), ts(at), id);
        int skipped = jdbc.update("UPDATE evaluation_case SET status='SKIPPED',failure_category='NOT_STARTED_AFTER_INTERRUPTION',"
                + "scores=?::jsonb,completed_at=? WHERE evaluation_id=? AND status='PENDING'",
                json(skippedScores), ts(at), id);
        var persistedCases = jdbc.query("SELECT * FROM evaluation_case WHERE evaluation_id=? ORDER BY case_id",
                (rs, row) -> readCase(rs), id);
        var aggregate = aggregate(persistedCases);
        jdbc.update("""
                UPDATE evaluation_run SET status='PARTIAL',completed_at=?,total_cases=?,completed_cases=?,
                passed_cases=?,failed_cases=?,model_calls=?,tool_calls=?,prompt_tokens=NULL,completion_tokens=NULL,
                estimated_cost_usd=NULL,failure_category='INTERRUPTED_OUTCOME_UNKNOWN',execution_owner=NULL,
                execution_fence=execution_fence+1,lease_expires_at=NULL WHERE evaluation_id=? AND status='RUNNING'""",
                ts(at), aggregate.totalCases(), aggregate.completedCases(), aggregate.passedCases(), aggregate.failedCases(),
                aggregate.modelCalls(), aggregate.toolCalls(), id);
        jdbc.update("INSERT INTO evaluation_status_event(evaluation_id,status,occurred_at,failure_category) VALUES (?, 'PARTIAL', ?, 'INTERRUPTED_OUTCOME_UNKNOWN')",
                id, ts(at));
        return unknown + skipped;
        });
    }

    private boolean lockOwner(EvaluationLease lease, Instant at, boolean requireUnexpired) {
        var rows = jdbc.query("SELECT status,execution_owner,execution_fence,lease_expires_at FROM evaluation_run "
                        + "WHERE evaluation_id=? FOR UPDATE", (rs, row) -> new Object[]{rs.getString(1), rs.getString(2),
                        rs.getLong(3), rs.getTimestamp(4)}, lease.evaluationId());
        if (rows.isEmpty()) return false;
        var row = rows.getFirst();
        if (!"RUNNING".equals(row[0]) || !lease.owner().equals(row[1]) || ((Long) row[2]) != lease.fence()) return false;
        Timestamp expiry = (Timestamp) row[3];
        return !requireUnexpired || expiry != null && expiry.toInstant().isAfter(at);
    }

    private static EvaluationAggregate aggregate(List<EvaluationCaseRecord> cases) {
        int completed = (int) cases.stream().filter(c -> c.status() == EvaluationCaseStatus.SUCCEEDED
                || c.status() == EvaluationCaseStatus.FAILED).count();
        int passed = (int) cases.stream().filter(c -> c.status() == EvaluationCaseStatus.SUCCEEDED
                && c.scores().stream().allMatch(s -> s.outcome() == EvaluationOutcome.PASS
                || s.outcome() == EvaluationOutcome.NOT_APPLICABLE)).count();
        int failed = (int) cases.stream().filter(c -> c.status() == EvaluationCaseStatus.FAILED
                || c.scores().stream().anyMatch(s -> s.outcome() == EvaluationOutcome.FAIL)).count();
        return new EvaluationAggregate(cases.size(), completed, passed, failed,
                cases.stream().mapToInt(EvaluationCaseRecord::modelCalls).sum(),
                cases.stream().mapToInt(EvaluationCaseRecord::toolCalls).sum(), null, null, null);
    }

    private EvaluationRunRecord readRun(ResultSet rs) throws SQLException {
        var dataset = new EvaluationDatasetIdentity(rs.getString("dataset_id"), rs.getString("dataset_version"),
                rs.getString("dataset_hash").trim(), rs.getInt("dataset_format_version"));
        var aggregate = new EvaluationAggregate(rs.getInt("total_cases"), rs.getInt("completed_cases"),
                rs.getInt("passed_cases"), rs.getInt("failed_cases"), rs.getInt("model_calls"), rs.getInt("tool_calls"),
                (Long) rs.getObject("prompt_tokens"), (Long) rs.getObject("completion_tokens"), rs.getBigDecimal("estimated_cost_usd"));
        return new EvaluationRunRecord(rs.getString("evaluation_id"), EvaluationRunStatus.valueOf(rs.getString("status")),
                dataset, rs.getString("git_commit"), rs.getString("workflow_mode"), rs.getString("provider"),
                rs.getString("model"), parse(rs.getString("evaluator_versions"), VERSIONS), rs.getInt("model_call_budget"),
                rs.getInt("tool_call_budget"), rs.getInt("max_cases"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("deadline_at")), instant(rs.getTimestamp("completed_at")), aggregate,
                rs.getString("failure_category"));
    }

    private EvaluationCaseRecord readCase(ResultSet rs) throws SQLException {
        return new EvaluationCaseRecord(rs.getString("evaluation_id"), rs.getString("case_id"),
                EvaluationCaseStatus.valueOf(rs.getString("status")), rs.getString("run_id"),
                rs.getString("failure_category"), parse(rs.getString("scores"), SCORES), rs.getInt("model_calls"),
                rs.getInt("tool_calls"), rs.getBoolean("token_usage_known"), (Long) rs.getObject("prompt_tokens"),
                (Long) rs.getObject("completion_tokens"), (Long) rs.getObject("duration_millis"),
                instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("completed_at")), rs.getInt("attempt"));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Unable to serialize evaluation metadata"); }
    }
    private <T> T parse(String value, TypeReference<T> type) {
        try { return mapper.readValue(value, type); }
        catch (Exception e) { throw new IllegalStateException("Unable to read evaluation metadata"); }
    }
    private static Timestamp ts(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
}
