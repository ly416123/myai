package com.example.agent.workflow;

import com.example.agent.client.ModelClient;
import com.example.agent.model.Message;
import com.example.agent.model.ModelResponse;
import com.example.agent.model.ToolCall;
import com.example.agent.model.ToolDefinition;
import com.example.agent.runtime.AgentRuntime;
import com.example.agent.client.ModelClientException;
import com.example.agent.tool.ToolExecutor;
import com.example.agent.tool.ToolRegistry;
import com.example.agent.tools.CalculatorTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 固定、无网络的基础工作流评测样例。 */
class AgentWorkflowEvaluationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void ordinaryAnswerCompletesWithoutToolsAndRecordsUnknownTokensAsUnknown() {
        ToolRegistry tools = new ToolRegistry();
        ModelClient model = (messages, definitions) -> new ModelResponse("你好", List.of());
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            InMemoryRunStore store = new InMemoryRunStore();
            AgentRuntime runtime = runtime(model, tools, executor, store, new RuntimeConfig(5, 3, 1, Duration.ofSeconds(5)));
            RunRecord run = runtime.runTracked("问候我");

            assertEquals(RunStatus.SUCCEEDED, run.status());
            assertEquals("你好", run.finalAnswer());
            assertEquals(1, run.modelCalls());
            assertEquals(0, run.toolCalls());
            assertNull(run.promptTokens());
            assertNull(run.completionTokens());
            assertTrue(run.steps().stream().allMatch(step -> step.stepId() != null));
            assertEquals(List.of(RunStatus.CREATED, RunStatus.RUNNING, RunStatus.SUCCEEDED),
                    run.stateChanges().stream().map(RunStateChange::status).toList());
        }
    }

    @Test
    void transientModelFailureRetriesWithinCallBudgetAndRecordsTokenUsage() {
        ToolRegistry tools = new ToolRegistry();
        AtomicInteger attempts = new AtomicInteger();
        ModelClient model = (messages, definitions) -> {
            if (attempts.getAndIncrement() == 0) throw new ModelClientException(FailureCategory.MODEL_RATE_LIMIT, true);
            return new ModelResponse("recovered", List.of(), 12L, 4L);
        };
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            InMemoryRunStore store = new InMemoryRunStore();
            AgentRuntime runtime = runtime(model, tools, executor, store, new RuntimeConfig(3, 3, 2, Duration.ofSeconds(5)));
            RunRecord run = runtime.runTracked("test retry");

            assertEquals(RunStatus.SUCCEEDED, run.status());
            assertEquals(2, run.modelCalls());
            assertNull(run.promptTokens(), "failed requests make whole-run token totals incomplete");
            assertFalse(run.tokenUsageKnown());
            assertTrue(run.steps().stream().anyMatch(step -> Long.valueOf(12).equals(step.promptTokens()) && Long.valueOf(4).equals(step.completionTokens())));
            assertTrue(run.steps().stream().anyMatch(step -> step.failureCategory() == FailureCategory.MODEL_RATE_LIMIT));
        }
    }

    @Test
    void terminalModelFailureIsNotRetried() {
        ToolRegistry tools = new ToolRegistry();
        AtomicInteger attempts = new AtomicInteger();
        ModelClient model = (messages, definitions) -> {
            attempts.incrementAndGet();
            throw new ModelClientException(FailureCategory.MODEL_AUTHENTICATION, false);
        };
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            AgentRuntime runtime = runtime(model, tools, executor, new InMemoryRunStore(),
                    new RuntimeConfig(5, 3, 3, Duration.ofSeconds(5)));
            RunRecord run = runtime.runTracked("terminal failure");

            assertEquals(RunStatus.FAILED, run.status());
            assertEquals(FailureCategory.MODEL_AUTHENTICATION, run.failureCategory());
            assertEquals(1, attempts.get());
            assertEquals(1, run.modelCalls());
        }
    }

    @Test
    void toolBudgetStopsBeforeExecutingOverBudgetToolCalls() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(new CalculatorTool());
        AtomicInteger executions = new AtomicInteger();
        ModelResponse toolResponse = new ModelResponse("", List.of(new ToolCall("call-1", "calculator",
                JSON.readTree("{\"operation\":\"add\",\"a\":1,\"b\":2}"))));
        ModelClient model = (messages, definitions) -> {
            executions.incrementAndGet();
            return toolResponse;
        };
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            InMemoryRunStore store = new InMemoryRunStore();
            AgentRuntime runtime = runtime(model, tools, executor, store, new RuntimeConfig(5, 0, 0, Duration.ofSeconds(5)));
            RunRecord run = runtime.runTracked("算一下");

            assertEquals(RunStatus.FAILED, run.status());
            assertEquals(FailureCategory.TOOL_BUDGET_EXCEEDED, run.failureCategory());
            assertEquals(0, run.toolCalls());
            assertEquals(1, executions.get());
        }
    }

    @Test
    void calculatorEvaluationChecksToolNameParametersResultAndCallCounts() {
        ToolRegistry tools = new ToolRegistry();
        tools.register(new CalculatorTool());
        AtomicInteger modelRequests = new AtomicInteger();
        AtomicReference<List<String>> exposedToolNames = new AtomicReference<>();
        ModelClient model = (messages, definitions) -> {
            exposedToolNames.set(definitions.stream().map(ToolDefinition::name).toList());
            if (modelRequests.getAndIncrement() == 0) {
                return new ModelResponse("", List.of(new ToolCall("eval-call", "calculator",
                        JSON.createObjectNode().put("operation", "multiply").put("a", 6).put("b", 7))));
            }
            return new ModelResponse("42", List.of());
        };
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            InMemoryRunStore store = new InMemoryRunStore();
            AgentRuntime runtime = runtime(model, tools, executor, store,
                    new RuntimeConfig(3, 2, 0, Duration.ofSeconds(5)));
            RunRecord run = runtime.runTracked("6 × 7");

            assertEquals(RunStatus.SUCCEEDED, run.status());
            assertEquals("42", run.finalAnswer());
            assertEquals(List.of("calculator"), exposedToolNames.get());
            assertEquals(2, run.modelCalls());
            assertEquals(1, run.toolCalls());
            assertEquals("calculator", run.steps().stream().filter(step -> step.kind() == StepKind.TOOL)
                    .findFirst().orElseThrow().toolName());
            assertTrue(run.messages().stream().anyMatch(message -> message.role() == Message.Role.TOOL
                    && message.content().contains("\"result\":42.0")));
        }
    }

    @Test
    void doesNotExecuteToolWhenFinalModelCallBudgetIsAlreadyExhausted() {
        ToolRegistry tools = new ToolRegistry();
        CountingTool tool = new CountingTool();
        tools.register(tool);
        ModelClient model = (messages, definitions) -> new ModelResponse("", List.of(
                new ToolCall("last-call", "counting", JSON.createObjectNode())));
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            InMemoryRunStore store = new InMemoryRunStore();
            AgentRuntime runtime = runtime(model, tools, executor, store,
                    new RuntimeConfig(1, 3, 0, Duration.ofSeconds(5)));
            RunRecord run = runtime.runTracked("must not dispatch");

            assertEquals(RunStatus.FAILED, run.status());
            assertEquals(FailureCategory.MAX_MODEL_CALLS, run.failureCategory());
            assertEquals(0, run.toolCalls());
            assertEquals(0, tool.executions.get());
            assertTrue(run.steps().stream().anyMatch(step -> step.failureCategory() == FailureCategory.MAX_MODEL_CALLS));
        }
    }

    @Test
    void resumesPendingToolCallButNeverReplaysInProgressToolCall() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        CountingTool tool = new CountingTool();
        tools.register(tool);
        ToolCall call = new ToolCall("call-pending", "counting", JSON.readTree("{}"));
        InMemoryRunStore store = new InMemoryRunStore();
        RunRecord run = interruptedRun("pending-run", call, RunStepStatus.PENDING, store);
        ModelClient model = (messages, definitions) -> new ModelResponse("finished", List.of());
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            AgentRuntime runtime = runtime(model, tools, executor, store, new RuntimeConfig(5, 5, 0, Duration.ofSeconds(5)));
            RunRecord recovered = runtime.resume(run.runId());
            assertEquals(RunStatus.SUCCEEDED, recovered.status());
            assertEquals(1, tool.executions.get());
            assertTrue(recovered.resumed());
        }

        CountingTool secondTool = new CountingTool();
        ToolRegistry secondRegistry = new ToolRegistry();
        secondRegistry.register(secondTool);
        InMemoryRunStore secondStore = new InMemoryRunStore();
        RunRecord uncertain = interruptedRun("uncertain-run",
                new ToolCall("call-unknown", "counting", JSON.readTree("{}")), RunStepStatus.IN_PROGRESS, secondStore);
        List<List<Message>> modelInputs = new ArrayList<>();
        ModelClient secondModel = (messages, definitions) -> {
            modelInputs.add(List.copyOf(messages));
            return new ModelResponse("handled safely", List.of());
        };
        try (ToolExecutor executor = new ToolExecutor(secondRegistry, Duration.ofSeconds(1))) {
            AgentRuntime runtime = runtime(secondModel, secondRegistry, executor, secondStore,
                    new RuntimeConfig(5, 5, 0, Duration.ofSeconds(5)));
            RunRecord recovered = runtime.resume(uncertain.runId());
            assertEquals(RunStatus.SUCCEEDED, recovered.status());
            assertEquals(0, secondTool.executions.get(), "unknown outcome must not be replayed");
            assertTrue(recovered.steps().stream().anyMatch(step -> step.status() == RunStepStatus.INDETERMINATE));
            assertTrue(modelInputs.getFirst().stream().anyMatch(message -> message.role() == Message.Role.TOOL
                    && message.content().contains("not replayed")));
        }
    }

    @Test
    void handlesToolFailureAsAClassifiedResultAndContinuesTheConversation() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        tools.register(new CalculatorTool());
        ModelResponse divideByZero = new ModelResponse("", List.of(new ToolCall("divide-call", "calculator",
                JSON.createObjectNode().put("operation", "divide").put("a", 5).put("b", 0))));
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> toolReply = new AtomicReference<>();
        ModelClient model = (messages, definitions) -> {
            if (requests.getAndIncrement() == 0) return divideByZero;
            toolReply.set(messages.getLast().content());
            return new ModelResponse("无法计算，因为除数为零。", List.of());
        };
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            InMemoryRunStore store = new InMemoryRunStore();
            AgentRuntime runtime = runtime(model, tools, executor, store,
                    new RuntimeConfig(5, 3, 0, Duration.ofSeconds(5)));
            RunRecord run = runtime.runTracked("5 除以 0");
            assertEquals(RunStatus.SUCCEEDED, run.status());
            assertEquals(1, run.toolCalls());
            assertEquals(FailureCategory.TOOL_EXECUTION,
                    run.steps().stream().filter(step -> step.kind() == StepKind.TOOL).findFirst().orElseThrow().failureCategory());
            assertTrue(toolReply.get().contains("Tool execution failed"));
            assertFalse(toolReply.get().contains("除数不能为零"), "exception text must not be persisted or forwarded");
        }
    }

    @Test
    void marksTaskDeadlineWhenModelDoesNotReturnInTime() {
        ToolRegistry tools = new ToolRegistry();
        ModelClient slowModel = (messages, definitions) -> {
            try { Thread.sleep(2_000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return new ModelResponse("too late", List.of());
        };
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            InMemoryRunStore store = new InMemoryRunStore();
            AgentRuntime runtime = runtime(slowModel, tools, executor, store,
                    new RuntimeConfig(5, 2, 1, Duration.ofMillis(150)));
            RunRecord run = runtime.runTracked("slow request");
            assertEquals(RunStatus.FAILED, run.status());
            assertEquals(FailureCategory.TASK_DEADLINE, run.failureCategory());
            assertTrue(run.steps().stream().filter(step -> step.kind() == StepKind.MODEL)
                    .allMatch(step -> step.status() != RunStepStatus.IN_PROGRESS));
        }
    }

    @Test
    void cancellationIsPersistedAndObservedDuringModelWait() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        CountDownLatch requestStarted = new CountDownLatch(1);
        AtomicInteger interruptedCalls = new AtomicInteger();
        ModelClient blockingModel = (messages, definitions) -> {
            requestStarted.countDown();
            try { Thread.sleep(5_000); }
            catch (InterruptedException interrupted) {
                interruptedCalls.incrementAndGet();
                Thread.currentThread().interrupt();
            }
            return new ModelResponse("late", List.of());
        };
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            InMemoryRunStore store = new InMemoryRunStore();
            AgentRuntime runtime = runtime(blockingModel, tools, executor, store,
                    new RuntimeConfig(5, 2, 0, Duration.ofSeconds(10)));
            AtomicReference<RunRecord> result = new AtomicReference<>();
            Thread worker = Thread.ofVirtual().start(() -> result.set(runtime.runTracked("cancel me")));
            assertTrue(requestStarted.await(2, TimeUnit.SECONDS));
            String runId = store.list().getFirst().runId();
            runtime.cancelRun(runId);
            worker.join(2_000);
            assertFalse(worker.isAlive());
            assertEquals(RunStatus.CANCELLED, result.get().status());
            assertEquals(FailureCategory.CANCELLED, result.get().failureCategory());
            assertEquals(1, interruptedCalls.get(), "cancel request should interrupt the active model task");
        }
    }

    @Test
    void cancellationRequestedAsModelReturnsWinsBeforeSuccessfulCommit() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch allowReturn = new CountDownLatch(1);
        AtomicInteger interruptedCalls = new AtomicInteger();
        ModelClient blockingModel = (messages, definitions) -> {
            requestStarted.countDown();
            try { allowReturn.await(); }
            catch (InterruptedException interrupted) {
                interruptedCalls.incrementAndGet();
                // 模拟 SDK/底层实现即使收到中断仍返回结果；Runtime 仍须尊重取消请求。
            }
            return new ModelResponse("must not commit", List.of());
        };
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            InMemoryRunStore store = new InMemoryRunStore();
            AgentRuntime runtime = runtime(blockingModel, tools, executor, store,
                    new RuntimeConfig(5, 2, 0, Duration.ofSeconds(10)));
            AtomicReference<RunRecord> result = new AtomicReference<>();
            Thread worker = Thread.ofVirtual().start(() -> result.set(runtime.runTracked("cancel before commit")));
            assertTrue(requestStarted.await(2, TimeUnit.SECONDS));
            String runId = store.list().getFirst().runId();

            runtime.cancelRun(runId);
            allowReturn.countDown();
            worker.join(2_000);

            assertFalse(worker.isAlive());
            assertEquals(1, interruptedCalls.get());
            assertEquals(RunStatus.CANCELLED, result.get().status());
            assertNull(result.get().finalAnswer());
            assertEquals(RunStatus.CANCELLED, store.find(runId).orElseThrow().status());
        }
    }

    @Test
    void anotherRuntimeInstanceCanRequestCancellationThroughRunStore() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        CountDownLatch requestStarted = new CountDownLatch(1);
        AtomicInteger interruptedCalls = new AtomicInteger();
        ModelClient blockingModel = (messages, definitions) -> {
            requestStarted.countDown();
            try { Thread.sleep(5_000); }
            catch (InterruptedException interrupted) {
                interruptedCalls.incrementAndGet();
                Thread.currentThread().interrupt();
            }
            return new ModelResponse("late", List.of());
        };
        InMemoryRunStore store = new InMemoryRunStore();
        try (ToolExecutor firstExecutor = new ToolExecutor(tools, Duration.ofSeconds(1));
             ToolExecutor secondExecutor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            RuntimeConfig config = new RuntimeConfig(5, 2, 0, Duration.ofSeconds(10));
            AgentRuntime activeRuntime = runtime(blockingModel, tools, firstExecutor, store, config);
            AgentRuntime cancellationRuntime = runtime(blockingModel, tools, secondExecutor, store, config);
            AtomicReference<RunRecord> result = new AtomicReference<>();
            Thread worker = Thread.ofVirtual().start(() -> result.set(activeRuntime.runTracked("cancel across instances")));
            assertTrue(requestStarted.await(2, TimeUnit.SECONDS));
            String runId = store.list().getFirst().runId();

            cancellationRuntime.cancelRun(runId);
            worker.join(2_000);

            assertFalse(worker.isAlive());
            assertEquals(1, interruptedCalls.get());
            assertEquals(RunStatus.CANCELLED, result.get().status());
            assertEquals(RunStatus.CANCELLED, store.find(runId).orElseThrow().status());
        }
    }

    @Test
    void toolTimeoutIsIndeterminateAndIsNotTreatedAsAReversibleFailure() throws Exception {
        ToolRegistry tools = new ToolRegistry();
        AtomicInteger sideEffectStarted = new AtomicInteger();
        tools.register(new SlowTool(sideEffectStarted));
        ModelResponse toolRequest = new ModelResponse("", List.of(new ToolCall("slow-call", "slow", JSON.createObjectNode())));
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient model = (messages, definitions) -> modelCalls.getAndIncrement() == 0
                ? toolRequest : new ModelResponse("completed with uncertain tool outcome", List.of());
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofMillis(25))) {
            InMemoryRunStore store = new InMemoryRunStore();
            AgentRuntime runtime = runtime(model, tools, executor, store,
                    new RuntimeConfig(5, 2, 0, Duration.ofSeconds(3)));
            RunRecord run = runtime.runTracked("run slow tool");
            RunStep toolStep = run.steps().stream().filter(step -> step.kind() == StepKind.TOOL).findFirst().orElseThrow();
            assertEquals(RunStepStatus.INDETERMINATE, toolStep.status());
            assertEquals(FailureCategory.TOOL_TIMEOUT_UNKNOWN, toolStep.failureCategory());
            assertEquals(1, sideEffectStarted.get());
            assertEquals(RunStatus.SUCCEEDED, run.status());
        }
    }

    private static AgentRuntime runtime(ModelClient model, ToolRegistry tools, ToolExecutor executor,
                                        RunStore store, RuntimeConfig config) {
        return new AgentRuntime(model, tools, executor, store, config);
    }

    private static RunRecord interruptedRun(String id, ToolCall call, RunStepStatus stepStatus, RunStore store) {
        RunRecord run = RunRecord.create(id, "recover test", Duration.ofMinutes(1));
        run.transitionTo(RunStatus.RUNNING, java.time.Instant.now(), null);
        if (stepStatus == RunStepStatus.IN_PROGRESS) run.setToolCalls(1);
        run.setResumed(false);
        run.addMessage(Message.user("recover test"));
        run.addMessage(Message.assistantToolCalls(List.of(call)));
        run.addStep(new RunStep("step-" + id, StepKind.TOOL, stepStatus,
                java.time.Instant.now(), stepStatus == RunStepStatus.PENDING ? null : java.time.Instant.now(),
                0, call.id(), call.name(), null, null, null));
        store.save(run);
        return run;
    }

    private static final class CountingTool implements com.example.agent.tool.AgentTool {
        private final AtomicInteger executions = new AtomicInteger();
        @Override public String name() { return "counting"; }
        @Override public String description() { return "test counter"; }
        @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() { return JSON.createObjectNode().put("type", "object").set("properties", JSON.createObjectNode()); }
        @Override public com.fasterxml.jackson.databind.JsonNode execute(com.fasterxml.jackson.databind.JsonNode arguments) {
            executions.incrementAndGet();
            return JSON.createObjectNode().put("ok", true);
        }
    }

    private static final class SlowTool implements com.example.agent.tool.AgentTool {
        private final AtomicInteger started;
        private SlowTool(AtomicInteger started) { this.started = started; }
        @Override public String name() { return "slow"; }
        @Override public String description() { return "interruptible slow test tool"; }
        @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() {
            return JSON.createObjectNode().put("type", "object").set("properties", JSON.createObjectNode());
        }
        @Override public com.fasterxml.jackson.databind.JsonNode execute(com.fasterxml.jackson.databind.JsonNode arguments)
                throws InterruptedException {
            started.incrementAndGet();
            Thread.sleep(5_000);
            return JSON.createObjectNode().put("finished", true);
        }
    }
}
