package com.example.agent.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EvaluationRepository {
    void create(EvaluationRunRecord run, List<String> caseIds);
    java.util.Optional<EvaluationLease> start(String evaluationId, String owner, Instant leaseExpiresAt, Instant at);
    boolean renew(EvaluationLease lease, Instant at, Instant leaseExpiresAt);
    boolean startCase(EvaluationLease lease, String caseId, Instant at);
    boolean saveCase(EvaluationLease lease, EvaluationCaseRecord result, Instant at);
    boolean finish(EvaluationLease lease, EvaluationRunStatus status, EvaluationAggregate aggregate,
                   Instant at, String failureCategory);
    Optional<EvaluationSnapshot> find(String evaluationId);
    List<EvaluationRunRecord> list(int limit);
    int recoverExpired(String evaluationId, Instant at);
}
