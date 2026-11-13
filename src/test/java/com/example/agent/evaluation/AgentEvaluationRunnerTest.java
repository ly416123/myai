package com.example.agent.evaluation;

import com.example.agent.collaboration.WorkerRegistry;
import com.example.agent.collaboration.CollaborationConfig;
import com.example.agent.model.ModelResponse;
import com.example.agent.model.ToolCall;
import com.example.agent.observability.AgentTelemetry;
import com.example.agent.tool.ToolExecutor;
import com.example.agent.tool.ToolRegistry;
import com.example.agent.tools.CalculatorTool;
import com.example.agent.workflow.InMemoryRunStore;
import com.example.agent.workflow.RuntimeConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.math.BigDecimal;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AgentEvaluationRunnerTest {
    @Test
    void recordsExpectedToolAndTokenOutcomesWithoutRequiringAProvider(@TempDir Path output) throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        var tools = new ToolRegistry();
        tools.register(new CalculatorTool());
        var addArguments = new ObjectMapper().readTree("{\"operation\":\"add\",\"a\":20,\"b\":22}");
        var dataset = new AgentEvaluationDataset("v1", List.of(
                new AgentEvaluationScenario("calculator-basic", EvaluationWorkflow.SINGLE_AGENT,
                        "Calculate 20 + 22", List.of("calculator"))));
        try (var executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            var runner = new AgentEvaluationRunner((messages, definitions) -> {
                modelCalls.incrementAndGet();
                if (messages.stream().noneMatch(message -> message.role() == com.example.agent.model.Message.Role.TOOL)) {
                    return new ModelResponse("", List.of(new ToolCall("call-1", "calculator", addArguments)), 10L, 4L);
                }
                return new ModelResponse("42", List.of(), 12L, 5L);
            }, tools, executor, new WorkerRegistry(), new InMemoryRunStore(), RuntimeConfig.defaults(10),
                    CollaborationConfig.defaults(),
                    new ObjectMapper().findAndRegisterModules(), AgentTelemetry.noop());
            var result = runner.run(dataset, "test-model", new AgentEvaluationConfig(true, 1, 5, 5,
                    Duration.ofMinutes(1), output, null, null));

            assertEquals(2, modelCalls.get());
            assertEquals(1, result.completedCases());
            assertEquals(1, result.passedCases());
            assertEquals(1, result.cases().getFirst().toolCalls());
            assertEquals(List.of("calculator"), result.cases().getFirst().actualTools());
            assertEquals(22L, result.cases().getFirst().promptTokens());
            assertEquals(9L, result.cases().getFirst().completionTokens());
            assertNull(result.cases().getFirst().estimatedCostUsd());
            assertTrue(Files.exists(result.reportPath()));
            assertTrue(Files.readString(result.reportPath()).contains("calculator-basic"));
            assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(result.reportPath().getParent())));
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(result.reportPath())));
        }
    }

    @Test
    void stopsBeforeStartingAnotherScenarioWhenGlobalModelBudgetIsExhausted(@TempDir Path output) throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        var tools = new ToolRegistry();
        var dataset = new AgentEvaluationDataset("v1", List.of(
                new AgentEvaluationScenario("first", EvaluationWorkflow.SINGLE_AGENT, "question one", List.of()),
                new AgentEvaluationScenario("second", EvaluationWorkflow.SINGLE_AGENT, "question two", List.of())));
        try (var executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            var runner = new AgentEvaluationRunner((messages, definitions) -> {
                modelCalls.incrementAndGet();
                return new ModelResponse("answer", List.of(), 3L, 2L);
            }, tools, executor, new WorkerRegistry(), new InMemoryRunStore(), RuntimeConfig.defaults(10),
                    CollaborationConfig.defaults(),
                    new ObjectMapper().findAndRegisterModules(), AgentTelemetry.noop());
            var result = runner.run(dataset, "test-model", new AgentEvaluationConfig(true, 2, 1, 0,
                    Duration.ofMinutes(1), output, null, null));

            assertEquals(1, modelCalls.get());
            assertEquals(1, result.completedCases());
            assertEquals(1, result.notRunCases());
            assertEquals("NOT_RUN_MODEL_BUDGET", result.cases().getLast().status());
        }
    }

    @Test
    void estimatesCostOnlyWhenRatesAndProviderUsageAreAvailable(@TempDir Path output) throws Exception {
        var tools = new ToolRegistry();
        try (var executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            var runner = new AgentEvaluationRunner((messages, definitions) ->
                    new ModelResponse("done", List.of(), 1_000L, 500L), tools, executor,
                    new WorkerRegistry(), new InMemoryRunStore(), RuntimeConfig.defaults(2),
                    CollaborationConfig.defaults(), new ObjectMapper().findAndRegisterModules(), AgentTelemetry.noop());
            var dataset = new AgentEvaluationDataset("cost-v1", List.of(new AgentEvaluationScenario("cost-case",
                    EvaluationWorkflow.SINGLE_AGENT, "simple answer", List.of())));
            var result = runner.run(dataset, "test-model", new AgentEvaluationConfig(true, 1, 2, 0,
                    Duration.ofMinutes(1), output, BigDecimal.ONE, new BigDecimal("2")));

            assertEquals(new BigDecimal("0.00200000"), result.estimatedCostUsd());
        }
    }

    @Test
    void disabledEvaluationRunnerRejectsExecutionBeforeCallingTheModel(@TempDir Path output) {
        AtomicInteger modelCalls = new AtomicInteger();
        var tools = new ToolRegistry();
        var dataset = new AgentEvaluationDataset("disabled-v1", List.of(new AgentEvaluationScenario(
                "disabled", EvaluationWorkflow.SINGLE_AGENT, "synthetic prompt", List.of())));
        try (var executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            var runner = new AgentEvaluationRunner((messages, definitions) -> {
                modelCalls.incrementAndGet();
                return new ModelResponse("unexpected", List.of());
            }, tools, executor, new WorkerRegistry(), new InMemoryRunStore(), RuntimeConfig.defaults(2),
                    CollaborationConfig.defaults(), new ObjectMapper().findAndRegisterModules(), AgentTelemetry.noop());

            assertThrows(IllegalStateException.class, () -> runner.run(dataset, "test-model",
                    new AgentEvaluationConfig(false, 1, 2, 0, Duration.ofMinutes(1), output, null, null)));
            assertEquals(0, modelCalls.get());
        }
    }

    @Test
    void versionedCheckedInDatasetLoadsWithoutAnyCredentialOrProvider() {
        var dataset = AgentEvaluationDatasetLoader.load(java.nio.file.Path.of("eval/scenarios-v1.json"),
                new ObjectMapper().findAndRegisterModules());

        assertEquals("2026-11-v1", dataset.version());
        assertEquals(5, dataset.scenarios().size());
        assertTrue(dataset.scenarios().stream().anyMatch(scenario -> scenario.expectedTools().contains("weather")));
    }

    @Test
    void recordsCollaborativePlanRouteForHumanReview(@TempDir Path output) throws Exception {
        var tools = new ToolRegistry();
        var workerRegistry = new WorkerRegistry();
        var modelCalls = new AtomicInteger();
        try (var executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            var runner = new AgentEvaluationRunner((messages, definitions) -> {
                if (modelCalls.getAndIncrement() == 0) {
                    return new ModelResponse("{\"route\":\"NO_SPLIT\",\"tasks\":[],\"deliverables\":[]}",
                            List.of(), 7L, 2L);
                }
                return new ModelResponse("A Java record 是用于简洁声明不可变数据载体的类。", List.of(), 5L, 8L);
            }, tools, executor, workerRegistry, new InMemoryRunStore(), RuntimeConfig.defaults(10),
                    CollaborationConfig.defaults(), new ObjectMapper().findAndRegisterModules(), AgentTelemetry.noop());
            var dataset = new AgentEvaluationDataset("plan-v1", List.of(new AgentEvaluationScenario("plan-route",
                    EvaluationWorkflow.COLLABORATIVE, "Explain a Java record briefly", List.of())));

            var result = runner.run(dataset, "test-model", new AgentEvaluationConfig(true, 1, 5, 5,
                    Duration.ofMinutes(1), output, null, null));

            assertEquals("NO_SPLIT", result.cases().getFirst().planRoute());
            assertTrue(result.cases().getFirst().planMatched());
            assertEquals(0, result.cases().getFirst().plannedTaskCount());
            assertEquals(2, result.cases().getFirst().modelCalls());
        }
    }
}
