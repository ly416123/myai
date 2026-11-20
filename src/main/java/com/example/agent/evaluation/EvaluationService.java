package com.example.agent.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.example.agent.observability.AgentTelemetry;
import io.micrometer.observation.Observation;

/** Coordinates a persisted evaluation lifecycle around the existing live-provider runner. */
public final class EvaluationService {
    private final EvaluationRepository repository;
    private final AgentEvaluationRunner runner;
    private final EvaluationEvaluatorRegistry evaluators;
    private final String gitCommit;
    private final AgentTelemetry telemetry;

    public EvaluationService(EvaluationRepository repository, AgentEvaluationRunner runner,
                             EvaluationEvaluatorRegistry evaluators, String gitCommit, AgentTelemetry telemetry) {
        this.repository = java.util.Objects.requireNonNull(repository);
        this.runner = java.util.Objects.requireNonNull(runner);
        this.evaluators = java.util.Objects.requireNonNull(evaluators);
        this.gitCommit = gitCommit;
        this.telemetry = java.util.Objects.requireNonNull(telemetry);
    }

    public EvaluationRunRecord run(AgentEvaluationDatasetSnapshot snapshot, String model,
                                   AgentEvaluationConfig config, String provider, String mode) {
        return runForConfiguration(snapshot, model, null, config, provider, mode);
    }

    public EvaluationRunRecord runForConfiguration(AgentEvaluationDatasetSnapshot snapshot, String model,
                                   String configurationHash, AgentEvaluationConfig config,
                                   String provider, String mode) {
        return runForConfiguration(snapshot, model, configurationHash, config, provider, mode, null);
    }

    public EvaluationRunRecord runForConfiguration(AgentEvaluationDatasetSnapshot snapshot,
                                   com.example.agent.configuration.AgentConfigRevision revision,
                                   AgentEvaluationConfig config, String provider, String mode) {
        if (revision == null) throw new IllegalArgumentException("Configuration revision is required");
        return runForConfiguration(snapshot, revision.document().model().model(), revision.contentHash(), config,
                provider, mode, com.example.agent.configuration.RunConfigurationSnapshot.forEvaluation(revision));
    }

    private EvaluationRunRecord runForConfiguration(AgentEvaluationDatasetSnapshot snapshot, String model,
                                   String configurationHash, AgentEvaluationConfig config,
                                   String provider, String mode,
                                   com.example.agent.configuration.RunConfigurationSnapshot configurationSnapshot) {
        if (snapshot == null) throw new IllegalArgumentException("Dataset snapshot is required");
        if (configurationHash != null && !configurationHash.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Evaluation configuration hash is invalid");
        }
        if (config == null || !config.enabled()) throw new IllegalStateException("Live model evaluation is disabled");
        var dataset = snapshot.dataset();
        dataset.scenarios().forEach(s -> evaluators.validate(s.evaluatorIds()));
        Instant started = Instant.now();
        String id = UUID.randomUUID().toString();
        var identity = new EvaluationDatasetIdentity(snapshot.datasetId(), snapshot.datasetVersion(),
                snapshot.contentSha256(), snapshot.formatVersion());
        var run = EvaluationRunRecord.created(id, identity, gitCommit, mode, provider, model, configurationHash, evaluators.versions(),
                config.maxModelCalls(), config.maxToolCalls(), config.maxCases(), started, started.plus(config.totalTimeout()));
        List<String> caseIds = dataset.scenarios().stream().map(AgentEvaluationScenario::id).toList();
        repository.create(run, caseIds);
        String owner = UUID.randomUUID().toString();
        var lease = repository.start(id, owner, started.plusSeconds(45), started)
                .orElseThrow(() -> new IllegalStateException("Evaluation could not be claimed"));
        try (var heartbeat = new EvaluationLeaseHeartbeat(repository, lease)) {
        var listener = new EvaluationProgressListener() {
            private final java.util.Map<String, Observation> observations = new java.util.HashMap<>();
            private final java.util.Map<String, Observation.Scope> scopes = new java.util.HashMap<>();
            @Override public void caseStarted(AgentEvaluationScenario scenario) {
                heartbeat.assertValid();
                if (!repository.startCase(lease, scenario.id(), Instant.now()))
                    throw new IllegalStateException("Evaluation case could not be claimed");
                var observation = telemetry.startEvaluationCase(id, scenario.id());
                observations.put(scenario.id(), observation);
                scopes.put(scenario.id(), observation.openScope());
            }
            @Override public void caseCompleted(AgentEvaluationScenario scenario, AgentEvaluationCaseResult result) {
                boolean observable = result.runId() != null;
                var status = result.status().startsWith("NOT_RUN") ? EvaluationCaseStatus.SKIPPED
                        : result.status().equals("INFRASTRUCTURE_ERROR") ? EvaluationCaseStatus.FAILED
                        : observable && result.completed() ? EvaluationCaseStatus.SUCCEEDED : EvaluationCaseStatus.FAILED;
                String failureCategory = result.failureCategory() == null
                        ? (result.status().equals("INFRASTRUCTURE_ERROR") ? "INFRASTRUCTURE_ERROR" : null)
                        : result.failureCategory().name();
                boolean tokens = result.tokenUsageKnown() && result.promptTokens() != null && result.completionTokens() != null;
                var saved = new EvaluationCaseRecord(id, scenario.id(), status, result.runId(), failureCategory,
                        result.scores(), result.modelCalls(), result.toolCalls(), tokens, tokens ? result.promptTokens() : null,
                        tokens ? result.completionTokens() : null, result.durationMillis(), null, Instant.now(),
                        status == EvaluationCaseStatus.SKIPPED ? 0 : 1);
                try {
                    heartbeat.assertValid();
                    if (!repository.saveCase(lease, saved, Instant.now()))
                        throw new IllegalStateException("Evaluation case result was already finalized");
                } finally {
                    telemetry.finishEvaluationCase(observations.remove(scenario.id()), scopes.remove(scenario.id()),
                            status.name(), result.runId());
                }
            }
        };
        try {
            var result = runner.run(dataset, model, config, listener, configurationSnapshot);
            int failed = (int) result.cases().stream().filter(c -> c.scores().stream()
                    .anyMatch(s -> s.outcome() == EvaluationOutcome.FAIL)).count();
            int completed = (int) result.cases().stream().filter(c -> c.runId() != null).count();
            var aggregate = new EvaluationAggregate(result.cases().size(), completed, result.passedCases(), failed,
                    result.modelCalls(), result.toolCalls(), result.tokenUsageKnown() ? result.promptTokens() : null,
                    result.tokenUsageKnown() ? result.completionTokens() : null, result.estimatedCostUsd());
            var status = result.cases().stream().allMatch(c -> c.runId() != null && c.completed())
                    ? EvaluationRunStatus.SUCCEEDED : EvaluationRunStatus.PARTIAL;
            if (!repository.finish(lease, status, aggregate, result.completedAt(), null))
                throw new IllegalStateException("Evaluation terminal state could not be persisted");
            return repository.find(id).orElseThrow().run();
        } catch (RuntimeException failure) {
            var snap = repository.find(id).orElse(null);
            if (snap != null) {
                // A RUNNING case means the model outcome may be unknown. Never replay it automatically.
                for (var c : snap.cases()) {
                    if (c.status() != EvaluationCaseStatus.RUNNING && c.status() != EvaluationCaseStatus.PENDING) continue;
                    var state = c.status() == EvaluationCaseStatus.RUNNING ? EvaluationCaseStatus.INDETERMINATE
                            : EvaluationCaseStatus.SKIPPED;
                    repository.saveCase(lease, new EvaluationCaseRecord(id, c.caseId(), state, null,
                            state == EvaluationCaseStatus.INDETERMINATE ? "INTERRUPTED_OUTCOME_UNKNOWN" : "NOT_STARTED_AFTER_ERROR",
                            unknownScores(state == EvaluationCaseStatus.INDETERMINATE ? "INTERRUPTED_OUTCOME_UNKNOWN"
                                    : "NOT_STARTED_AFTER_ERROR"), 0, 0, false, null, null, null, c.startedAt(), Instant.now(),
                            c.attempt()), Instant.now());
                }
                var finalState = repository.find(id).orElseThrow();
                repository.finish(lease, EvaluationRunStatus.FAILED, aggregate(finalState), Instant.now(), "EVALUATION_ERROR");
            }
            throw failure;
        }
        }
    }

    public int markInterruptedAsIndeterminate(String evaluationId) {
        var snapshot = repository.find(evaluationId).orElseThrow(() -> new IllegalArgumentException("Evaluation not found"));
        if (snapshot.run().status() != EvaluationRunStatus.RUNNING) return 0;
        if (snapshot.run().deadlineAt().isAfter(Instant.now()))
            throw new IllegalStateException("Evaluation deadline has not elapsed; active work cannot be recovered yet");
        if (snapshot.leaseExpiresAt() != null && snapshot.leaseExpiresAt().isAfter(Instant.now()))
            throw new IllegalStateException("Evaluation execution lease is still active");
        return repository.recoverExpired(evaluationId, Instant.now());
    }

    private static EvaluationAggregate aggregate(EvaluationSnapshot snapshot) {
        int completed = (int) snapshot.cases().stream().filter(c -> c.status() == EvaluationCaseStatus.SUCCEEDED
                || c.status() == EvaluationCaseStatus.FAILED).count();
        int passed = (int) snapshot.cases().stream().filter(c -> c.status() == EvaluationCaseStatus.SUCCEEDED
                && c.scores().stream().allMatch(s -> s.outcome() == EvaluationOutcome.PASS
                || s.outcome() == EvaluationOutcome.NOT_APPLICABLE)).count();
        int failed = (int) snapshot.cases().stream().filter(c -> c.status() == EvaluationCaseStatus.FAILED
                || c.scores().stream().anyMatch(s -> s.outcome() == EvaluationOutcome.FAIL)).count();
        int models = snapshot.cases().stream().mapToInt(EvaluationCaseRecord::modelCalls).sum();
        int tools = snapshot.cases().stream().mapToInt(EvaluationCaseRecord::toolCalls).sum();
        return new EvaluationAggregate(snapshot.cases().size(), completed, passed, failed, models, tools, null, null, null);
    }

    private List<EvaluationScore> unknownScores(String reason) {
        return evaluators.versions().entrySet().stream()
                .map(e -> EvaluationScore.of(e.getKey(), e.getValue(), EvaluationOutcome.UNKNOWN, reason)).toList();
    }
}
