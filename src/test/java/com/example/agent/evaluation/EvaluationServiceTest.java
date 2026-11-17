package com.example.agent.evaluation;

import com.example.agent.collaboration.CollaborationConfig;
import com.example.agent.collaboration.WorkerRegistry;
import com.example.agent.client.ModelClient;
import com.example.agent.client.ModelRequestOptions;
import com.example.agent.configuration.AgentConfigDocument;
import com.example.agent.configuration.AgentConfigRevision;
import com.example.agent.configuration.ModelSettings;
import com.example.agent.configuration.RuntimeSettings;
import com.example.agent.model.ModelResponse;
import com.example.agent.observability.AgentTelemetry;
import com.example.agent.tool.ToolExecutor;
import com.example.agent.tool.ToolRegistry;
import com.example.agent.workflow.InMemoryRunStore;
import com.example.agent.workflow.RuntimeConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationServiceTest {
    @Test void associatesFakeEvaluationWithTheConfigurationRevisionHash(@TempDir Path output) {
        var repo = new FakeRepository();
        var tools = new ToolRegistry();
        var revision = AgentConfigRevision.create(new AgentConfigDocument(1, "candidate system prompt", List.of(),
                new ModelSettings("deepseek", "candidate-model", Map.of("temperature", 0.3),
                        "secret://deepseek/api-key"), Set.of(), Map.of(),
                new RuntimeSettings(4, 4, 0, 60_000, 10, 2)), new ObjectMapper().findAndRegisterModules());
        AtomicReference<List<com.example.agent.model.Message>> modelMessages = new AtomicReference<>();
        AtomicReference<ModelRequestOptions> modelOptions = new AtomicReference<>();
        ModelClient fakeModel = new ModelClient() {
            @Override public ModelResponse complete(List<com.example.agent.model.Message> messages,
                                                    List<com.example.agent.model.ToolDefinition> definitions) {
                return new ModelResponse("synthetic response", List.of(), 3L, 2L);
            }
            @Override public ModelResponse complete(List<com.example.agent.model.Message> messages,
                    List<com.example.agent.model.ToolDefinition> definitions, ModelRequestOptions options) {
                modelMessages.set(List.copyOf(messages));
                modelOptions.set(options);
                return new ModelResponse("synthetic response", List.of(), 3L, 2L);
            }
        };
        try (var executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            var runner = new AgentEvaluationRunner(fakeModel, tools, executor, new WorkerRegistry(), new InMemoryRunStore(), RuntimeConfig.defaults(4),
                    CollaborationConfig.defaults(), new ObjectMapper().findAndRegisterModules(), AgentTelemetry.noop());
            var service = new EvaluationService(repo, runner, EvaluationEvaluatorRegistry.defaults(), "fake-commit", AgentTelemetry.noop());
            var dataset = new AgentEvaluationDataset("revision-v1", List.of(new AgentEvaluationScenario(
                    "case-1", EvaluationWorkflow.SINGLE_AGENT, "synthetic prompt", List.of())));
            var snapshot = new AgentEvaluationDatasetSnapshot("revision-dataset", 1, "a".repeat(64), dataset);

            var result = service.runForConfiguration(snapshot, revision, config(output), "fake", "single");

            assertEquals(revision.contentHash(), result.configurationHash());
            assertEquals(revision.contentHash(), repo.find(result.evaluationId()).orElseThrow().run().configurationHash());
            assertEquals("candidate system prompt", modelMessages.get().getFirst().content());
            assertEquals("candidate-model", modelOptions.get().model());
            assertEquals(0.3, ((Number) modelOptions.get().parameters().get("temperature")).doubleValue());
        }
    }

    @Test void fakeModelEvaluationPersistsPerCaseAndTerminalOutcome(@TempDir Path output) {
        var repo = new FakeRepository();
        var tools = new ToolRegistry();
        try (var executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            var runner = new AgentEvaluationRunner((messages, definitions) -> new ModelResponse("synthetic response", List.of(), 3L, 2L),
                    tools, executor, new WorkerRegistry(), new InMemoryRunStore(), RuntimeConfig.defaults(4),
                    CollaborationConfig.defaults(), new ObjectMapper().findAndRegisterModules(), AgentTelemetry.noop());
            var service = new EvaluationService(repo, runner, EvaluationEvaluatorRegistry.defaults(), "fake-commit", AgentTelemetry.noop());
            var dataset = new AgentEvaluationDataset("fake-v1", List.of(
                    new AgentEvaluationScenario("case-1", EvaluationWorkflow.SINGLE_AGENT, "synthetic prompt", List.of()),
                    new AgentEvaluationScenario("case-2", EvaluationWorkflow.SINGLE_AGENT, "second synthetic prompt", List.of())));
            var snapshot = new AgentEvaluationDatasetSnapshot("fake-dataset", 1, "a".repeat(64), dataset);

            var limited = new AgentEvaluationConfig(true, 1, 4, 4, Duration.ofMinutes(1), output, null, null);
            var result = service.run(snapshot, "fake-model", limited, "fake", "dataset");

            assertEquals(EvaluationRunStatus.PARTIAL, result.status());
            var saved = repo.find(result.evaluationId()).orElseThrow();
            assertEquals(EvaluationCaseStatus.SUCCEEDED, saved.cases().getFirst().status());
            assertEquals(1, saved.cases().getFirst().modelCalls());
            assertTrue(saved.cases().getFirst().tokenUsageKnown());
            assertEquals(EvaluationCaseStatus.SKIPPED, saved.cases().getLast().status());
            assertEquals(2, saved.run().aggregate().totalCases());
            assertEquals(1, saved.run().aggregate().passedCases());
        }
    }

    @Test void rejectsDisabledEvaluationBeforeCreatingExperiment(@TempDir Path output) {
        var repo = new FakeRepository();
        var tools = new ToolRegistry();
        try (var executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            var runner = new AgentEvaluationRunner((messages, definitions) -> { throw new AssertionError("model called"); },
                    tools, executor, new WorkerRegistry(), new InMemoryRunStore(), RuntimeConfig.defaults(4),
                    CollaborationConfig.defaults(), new ObjectMapper(), AgentTelemetry.noop());
            var service = new EvaluationService(repo, runner, EvaluationEvaluatorRegistry.defaults(), null, AgentTelemetry.noop());
            var dataset = new AgentEvaluationDataset("disabled-v1", List.of(new AgentEvaluationScenario(
                    "case-1", EvaluationWorkflow.SINGLE_AGENT, "synthetic prompt", List.of())));
            var snapshot = new AgentEvaluationDatasetSnapshot("fixture", 1, "b".repeat(64), dataset);
            assertThrows(IllegalStateException.class, () -> service.run(snapshot, "fake-model", config(output, false), "fake", "dataset"));
            assertTrue(repo.runs.isEmpty());
        }
    }

    private static AgentEvaluationConfig config(Path output) {
        return config(output, true);
    }
    private static AgentEvaluationConfig config(Path output, boolean enabled) {
        return new AgentEvaluationConfig(enabled, 5, 4, 4, Duration.ofMinutes(1), output, null, null);
    }

    private static final class FakeRepository implements EvaluationRepository {
        private final java.util.Map<String, EvaluationRunRecord> runs = new java.util.HashMap<>();
        private final java.util.Map<String, List<EvaluationCaseRecord>> cases = new java.util.HashMap<>();
        @Override public void create(EvaluationRunRecord run, List<String> ids) {
            runs.put(run.evaluationId(), run);
            cases.put(run.evaluationId(), new ArrayList<>(ids.stream().map(id -> new EvaluationCaseRecord(run.evaluationId(), id,
                    EvaluationCaseStatus.PENDING, null, null, List.of(), 0, 0, false, null, null, null, null, null, 0)).toList()));
        }
        @Override public Optional<EvaluationLease> start(String id, String owner, Instant leaseExpiresAt, Instant at) {
            var run = runs.get(id); if (run.status() != EvaluationRunStatus.CREATED) return Optional.empty();
            runs.put(id, copy(run, EvaluationRunStatus.RUNNING, null, run.aggregate()));
            return Optional.of(new EvaluationLease(id, owner, 1));
        }
        @Override public boolean renew(EvaluationLease lease, Instant at, Instant leaseExpiresAt) { return true; }
        @Override public boolean startCase(EvaluationLease lease, String caseId, Instant at) {
            String id = lease.evaluationId();
            int index = index(id, caseId); var c = cases.get(id).get(index);
            if (c.status() != EvaluationCaseStatus.PENDING) return false;
            cases.get(id).set(index, new EvaluationCaseRecord(id, caseId, EvaluationCaseStatus.RUNNING, null, null,
                    List.of(), 0, 0, false, null, null, null, at, null, 1)); return true;
        }
        @Override public boolean saveCase(EvaluationLease lease, EvaluationCaseRecord value, Instant at) {
            int index = index(value.evaluationId(), value.caseId()); var existing = cases.get(value.evaluationId()).get(index);
            if (existing.status() != EvaluationCaseStatus.RUNNING && existing.status() != EvaluationCaseStatus.PENDING) return false;
            cases.get(value.evaluationId()).set(index, value); return true;
        }
        @Override public boolean finish(EvaluationLease lease, EvaluationRunStatus status, EvaluationAggregate aggregate, Instant at, String failure) {
            String id = lease.evaluationId();
            var run = runs.get(id); if (run.status() != EvaluationRunStatus.RUNNING) return false;
            runs.put(id, copy(run, status, at, aggregate)); return true;
        }
        @Override public Optional<EvaluationSnapshot> find(String id) {
            return Optional.ofNullable(runs.get(id)).map(run -> new EvaluationSnapshot(run, cases.get(id)));
        }
        @Override public List<EvaluationRunRecord> list(int limit) { return List.copyOf(runs.values()); }
        @Override public int recoverExpired(String id, Instant at) { return 0; }
        private int index(String id, String caseId) {
            for (int i = 0; i < cases.get(id).size(); i++) if (cases.get(id).get(i).caseId().equals(caseId)) return i;
            throw new IllegalArgumentException("unknown case");
        }
        private static EvaluationRunRecord copy(EvaluationRunRecord r, EvaluationRunStatus status, Instant ended,
                                                EvaluationAggregate aggregate) {
            return new EvaluationRunRecord(r.evaluationId(), status, r.dataset(), r.gitCommit(), r.workflowMode(),
                    r.provider(), r.model(), r.configurationHash(), r.evaluatorVersions(),
                    r.modelCallBudget(), r.toolCallBudget(), r.maxCases(),
                    r.createdAt(), r.deadlineAt(), ended, aggregate, null);
        }
    }
}
