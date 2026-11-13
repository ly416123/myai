package com.example.agent.collaboration;

import com.example.agent.client.ModelClient;
import com.example.agent.client.ModelClientException;
import com.example.agent.model.ModelResponse;
import com.example.agent.model.ToolCall;
import com.example.agent.observability.AgentTelemetry;
import com.example.agent.runtime.AgentRuntime;
import com.example.agent.tool.AgentTool;
import com.example.agent.tool.ToolExecutor;
import com.example.agent.tool.ToolRegistry;
import com.example.agent.workflow.FailureCategory;
import com.example.agent.workflow.InMemoryRunStore;
import com.example.agent.workflow.RunRecord;
import com.example.agent.workflow.RunStatus;
import com.example.agent.workflow.RunStep;
import com.example.agent.workflow.RunStepStatus;
import com.example.agent.workflow.RuntimeConfig;
import com.example.agent.workflow.RunType;
import com.example.agent.workflow.StepKind;
import com.example.agent.workflow.RunLeasePolicy;
import com.example.agent.workflow.RunStoreUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

class CollaborativeWorkflowTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void propagatesRunAndModelStepObservationsToCollaborativeModelClientThread() {
        ToolRegistry tools = tools();
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override public boolean supportsContext(Observation.Context context) { return true; }
        });
        ConcurrentLinkedQueue<String> activeObservations = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> parentObservations = new ConcurrentLinkedQueue<>();
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (messages, definitions) -> {
            Observation current = observations.getCurrentObservation();
            activeObservations.add(current == null ? "NONE" : current.getContextView().getName());
            Observation parent = current == null ? null : (Observation) current.getContextView().getParentObservation();
            parentObservations.add(parent == null ? "NONE" : parent.getContextView().getName());
            if (calls.getAndIncrement() == 0)
                return plan("{\"route\":\"NO_SPLIT\",\"tasks\":[],\"deliverables\":[]}");
            return new ModelResponse("done", List.of());
        };
        InMemoryRunStore store = new InMemoryRunStore();
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            var workflow = new CollaborativeWorkflow(model, tools, workers(), store,
                    new CollaborationConfig(3, 1, 5, 2, 0, Duration.ofSeconds(3), Duration.ofSeconds(1)),
                    JSON, new AgentTelemetry(null, observations));
            assertEquals(RunStatus.SUCCEEDED, workflow.runTracked("trace propagation").status());
        }

        assertEquals(List.of("agent.step", "agent.step"), List.copyOf(activeObservations));
        assertEquals(List.of("agent.run", "agent.run"), List.copyOf(parentObservations));
    }

    @Test
    void nestsLocalWorkerAndModelObservationsUnderRunAndWorkerStep() {
        ToolRegistry tools = tools();
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override public boolean supportsContext(Observation.Context context) { return true; }
        });
        AtomicReference<String> workerModelParentKind = new AtomicReference<>();
        AtomicReference<String> workerParentObservation = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (messages, definitions) -> {
            if (isPlannerRequest(messages)) {
                return plan("""
                        {"route":"DELEGATE","tasks":[
                          {"taskId":"t-number","workerId":"numbers","description":"calculate 6 times 7",
                           "dependencies":[],"deliverableIds":["number"]}],
                          "deliverables":[{"deliverableId":"number","description":"calculation"}]}
                        """);
            }
            if (messages.getFirst().content().equals("numbers prompt")) {
                Observation current = observations.getCurrentObservation();
                var workerStep = current == null ? null : current.getContextView().getParentObservation();
                if (workerStep != null) {
                    var kind = workerStep.getContextView().getLowCardinalityKeyValue("step.kind");
                    workerModelParentKind.set(kind == null ? "NONE" : kind.getValue());
                    var run = workerStep.getContextView().getParentObservation();
                    workerParentObservation.set(run == null ? "NONE" : run.getContextView().getName());
                }
                return new ModelResponse("42", List.of());
            }
            calls.incrementAndGet();
            return new ModelResponse("The answer is 42.", List.of());
        };
        InMemoryRunStore store = new InMemoryRunStore();
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            var workflow = new CollaborativeWorkflow(model, tools, workers(), store,
                    new CollaborationConfig(5, 2, 10, 5, 0, Duration.ofSeconds(5), Duration.ofSeconds(1)),
                    JSON, new AgentTelemetry(null, observations));
            RunRecord result = workflow.runTracked("calculate and explain");
            assertEquals(RunStatus.SUCCEEDED, result.status(),
                    () -> "failure=" + result.failureCategory() + " tasks=" + result.childTasks());
        }

        assertEquals("WORKER", workerModelParentKind.get());
        assertEquals("agent.run", workerParentObservation.get());
        assertEquals(1, calls.get());
    }

    @Test
    void temporaryStoreReadFailureWhilePollingSupervisorModelDoesNotAbortRun() throws Exception {
        ToolRegistry tools = tools();
        InMemoryRunStore store = spy(new InMemoryRunStore());
        doThrow(new RunStoreUnavailableException(new IllegalStateException()))
                .doCallRealMethod().when(store).find(anyString());
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (messages, definitions) -> calls.getAndIncrement() == 0
                ? plan("{\"route\":\"NO_SPLIT\",\"tasks\":[],\"deliverables\":[]}")
                : new ModelResponse("Supervisor recovered", List.of());

        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            RunRecord result = workflow(model, tools, workers(), executor, store,
                    new CollaborationConfig(3, 1, 5, 2, 0, Duration.ofSeconds(3), Duration.ofSeconds(1)))
                    .runTracked("temporary database outage");

            assertEquals(RunStatus.SUCCEEDED, result.status());
            assertEquals("Supervisor recovered", result.finalAnswer());
            assertEquals(2, result.modelCalls());
        }
    }

    @Test
    void delegateUsesRegisteredWorkersIsolatesToolsAndRunsIndependentTasksConcurrently() throws Exception {
        ToolRegistry tools = tools();
        WorkerRegistry workers = workers();
        CountDownLatch bothWorkersEntered = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicReference<List<String>> numberTools = new AtomicReference<>();
        AtomicReference<List<String>> writerTools = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (messages, definitions) -> {
            int index = calls.getAndIncrement();
            if (index == 0) return plan("""
                    {"route":"DELEGATE","tasks":[
                      {"taskId":"t-number","workerId":"numbers","description":"calculate 6 times 7","dependencies":[],"deliverableIds":["number"]},
                      {"taskId":"t-summary","workerId":"writer","description":"explain the supplied result","dependencies":[],"deliverableIds":["explanation"]}],
                      "deliverables":[{"deliverableId":"number","description":"calculation"},{"deliverableId":"explanation","description":"explanation"}]}
                    """);
            String prompt = messages.getFirst().content();
            if (prompt.equals("numbers prompt")) numberTools.set(definitions.stream().map(d -> d.name()).toList());
            if (prompt.equals("writer prompt")) writerTools.set(definitions.stream().map(d -> d.name()).toList());
            if (prompt.equals("numbers prompt") && messages.stream().noneMatch(m -> m.role() == com.example.agent.model.Message.Role.TOOL)) {
                int now = active.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                bothWorkersEntered.countDown();
                try {
                    assertTrue(bothWorkersEntered.await(2, TimeUnit.SECONDS), "independent workers should overlap");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("worker test interrupted", interrupted);
                }
                active.decrementAndGet();
                return new ModelResponse("", List.of(new ToolCall("number-call", "calculator",
                        JSON.createObjectNode().put("operation", "multiply").put("a", 6).put("b", 7))), 3L, 2L);
            }
            if (prompt.equals("numbers prompt")) return new ModelResponse("42", List.of(), 2L, 1L);
            if (prompt.equals("writer prompt")) {
                int now = active.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                bothWorkersEntered.countDown();
                try {
                    assertTrue(bothWorkersEntered.await(2, TimeUnit.SECONDS), "independent workers should overlap");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("worker test interrupted", interrupted);
                }
                active.decrementAndGet();
                return new ModelResponse("42 is the result.", List.of(), 3L, 2L);
            }
            return new ModelResponse("number=42; explanation=42 is the result.", List.of(), 4L, 3L);
        };
        InMemoryRunStore store = new InMemoryRunStore();
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            CollaborativeWorkflow workflow = workflow(model, tools, workers, executor, store,
                    new CollaborationConfig(5, 2, 10, 5, 0, Duration.ofSeconds(5), Duration.ofSeconds(1)));
            RunRecord run = workflow.runTracked("calculate a value and explain it");

            assertEquals(RunStatus.SUCCEEDED, run.status());
            assertEquals(5, run.modelCalls());
            assertEquals(1, run.toolCalls());
            assertEquals(2, run.childTasks().size());
            assertEquals(2, peak.get());
            assertEquals(List.of("calculator"), numberTools.get());
            assertEquals(List.of("weather"), writerTools.get());
            assertTrue(run.steps().stream().filter(step -> step.kind() == StepKind.MODEL)
                    .allMatch(step -> step.stepId() != null && step.durationMillis() >= 0));
            assertTrue(run.steps().stream().anyMatch(step -> step.kind() == StepKind.TOOL
                    && "t-number".equals(step.taskId()) && "numbers".equals(step.workerId())));
            assertEquals(1, store.list().size(), "children are stored inside the single parent Run");
        }
    }

    @Test
    void noSplitUsesSingleAgentToolLoopInSameParentRunAndSharesBudget() throws Exception {
        ToolRegistry tools = tools();
        WorkerRegistry workers = workers();
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (messages, definitions) -> switch (calls.getAndIncrement()) {
            case 0 -> plan("{\"route\":\"NO_SPLIT\",\"tasks\":[],\"deliverables\":[]}");
            case 1 -> {
                assertEquals(2, definitions.size(), "single-agent fallback receives the configured full tool set");
                yield new ModelResponse("", List.of(new ToolCall("single-tool", "calculator",
                        JSON.createObjectNode().put("operation", "multiply").put("a", 6).put("b", 7))));
            }
            default -> new ModelResponse("42", List.of());
        };
        InMemoryRunStore store = new InMemoryRunStore();
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            CollaborativeWorkflow workflow = workflow(model, tools, workers, executor, store,
                    new CollaborationConfig(5, 2, 4, 2, 0, Duration.ofSeconds(5), Duration.ofSeconds(1)));
            RunRecord run = workflow.runTracked("6 times 7");
            assertEquals(RunStatus.SUCCEEDED, run.status());
            assertEquals("42", run.finalAnswer());
            assertTrue(run.childTasks().isEmpty());
            assertEquals(3, run.modelCalls());
            assertEquals(1, run.toolCalls());
            assertEquals(1, store.list().size());
        }
    }

    @Test
    void invalidPlanFailsBeforeAnyWorkerStarts() {
        ToolRegistry tools = tools();
        WorkerRegistry workers = workers();
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (messages, definitions) -> {
            calls.incrementAndGet();
            return plan("{\"route\":\"DELEGATE\",\"tasks\":[{\"taskId\":\"x\",\"workerId\":\"unknown\",\"description\":\"bad\",\"dependencies\":[],\"deliverableIds\":[\"d\"]}],\"deliverables\":[{\"deliverableId\":\"d\",\"description\":\"D\"}]}");
        };
        InMemoryRunStore store = new InMemoryRunStore();
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            CollaborativeWorkflow workflow = workflow(model, tools, workers, executor, store,
                    new CollaborationConfig(5, 2, 4, 2, 0, Duration.ofSeconds(5), Duration.ofSeconds(1)));
            RunRecord run = workflow.runTracked("bad plan");
            assertEquals(RunStatus.FAILED, run.status());
            assertEquals(FailureCategory.PLAN_INVALID, run.failureCategory());
            assertTrue(run.childTasks().isEmpty());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void malformedPlanJsonIsControlledFailureAndNeverFallsBackToSingleAgent() {
        ToolRegistry tools = tools();
        WorkerRegistry workers = workers();
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (messages, definitions) -> {
            calls.incrementAndGet();
            return plan("{not-valid-json");
        };
        InMemoryRunStore store = new InMemoryRunStore();
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            RunRecord run = workflow(model, tools, workers, executor, store,
                    new CollaborationConfig(5, 2, 4, 2, 0, Duration.ofSeconds(5), Duration.ofSeconds(1)))
                    .runTracked("malformed plan");
            assertEquals(RunStatus.FAILED, run.status());
            assertEquals(FailureCategory.PLAN_INVALID, run.failureCategory());
            assertTrue(run.childTasks().isEmpty());
            assertEquals(1, calls.get(), "invalid plan must not start fallback agent or a Worker");
        }
    }

    @Test
    void sharedModelBudgetIncludesPlanningAndPreventsWorkerOverrun() {
        ToolRegistry tools = tools();
        WorkerRegistry workers = workers();
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (messages, definitions) -> {
            calls.incrementAndGet();
            if (isPlannerRequest(messages)) return plan("""
                    {"route":"DELEGATE","tasks":[{"taskId":"t","workerId":"numbers","description":"calculate","dependencies":[],"deliverableIds":["d"]}],"deliverables":[{"deliverableId":"d","description":"answer"}]}
                    """);
            return new ModelResponse("42", List.of());
        };
        InMemoryRunStore store = new InMemoryRunStore();
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            CollaborativeWorkflow workflow = workflow(model, tools, workers, executor, store,
                    new CollaborationConfig(5, 1, 1, 2, 0, Duration.ofSeconds(5), Duration.ofSeconds(1)));
            RunRecord run = workflow.runTracked("budget");
            assertEquals(RunStatus.FAILED, run.status());
            assertEquals(FailureCategory.MAX_MODEL_CALLS, run.failureCategory());
            assertEquals(1, run.modelCalls());
            assertEquals(0, calls.get() - 1, "planner consumed the only model call");
            assertEquals(TaskStatus.SKIPPED, run.childTasks().getFirst().status(),
                    "no Worker runs after the planner consumes the whole parent model budget");
        }
    }

    @Test
    void dependencyTasksRunAfterTheirPrerequisitesAndReceiveOnlyDeclaredResults() {
        ToolRegistry tools = tools();
        WorkerRegistry workers = workers();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<List<String>> order = new AtomicReference<>(new java.util.concurrent.CopyOnWriteArrayList<>());
        ModelClient model = (messages, definitions) -> {
            calls.incrementAndGet();
            if (isPlannerRequest(messages)) return plan("""
                    {"route":"DELEGATE","tasks":[
                    {"taskId":"base","workerId":"numbers","description":"produce base value","dependencies":[],"deliverableIds":["base-output"]},
                    {"taskId":"dependent","workerId":"writer","description":"explain base value","dependencies":["base"],"deliverableIds":["explanation"]}],
                    "deliverables":[{"deliverableId":"base-output","description":"Base"},{"deliverableId":"explanation","description":"Explanation"}]}
                    """);
            String worker = messages.getFirst().content();
            if (worker.equals("numbers prompt")) { order.get().add("base"); return new ModelResponse("BASE-RESULT", List.of()); }
            if (worker.equals("writer prompt")) {
                order.get().add("dependent");
                assertTrue(messages.getLast().content().contains("BASE-RESULT"));
                assertFalse(messages.getLast().content().contains("unrelated"));
                return new ModelResponse("EXPLAINED", List.of());
            }
            return new ModelResponse("complete", List.of());
        };
        InMemoryRunStore store = new InMemoryRunStore();
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            RunRecord run = workflow(model, tools, workers, executor, store,
                    new CollaborationConfig(4, 2, 10, 4, 0, Duration.ofSeconds(5), Duration.ofSeconds(1)))
                    .runTracked("produce base then explain");
            assertEquals(RunStatus.SUCCEEDED, run.status());
            assertEquals(List.of("base", "dependent"), order.get());
            assertEquals(4, calls.get());
        }
    }

    @Test
    void partialFailureSkipsDependentTasksAndReturnsExplicitMissingDeliverables() {
        ToolRegistry tools = tools();
        WorkerRegistry workers = workers();
        ModelClient model = (messages, definitions) -> {
            if (isPlannerRequest(messages)) return plan("""
                    {"route":"DELEGATE","tasks":[
                    {"taskId":"bad","workerId":"numbers","description":"fail this task","dependencies":[],"deliverableIds":["lost"]},
                    {"taskId":"good","workerId":"writer","description":"produce available output","dependencies":[],"deliverableIds":["kept"]},
                    {"taskId":"downstream","workerId":"numbers","description":"use failed task","dependencies":["bad"],"deliverableIds":["downstream-output"]}],
                    "deliverables":[{"deliverableId":"lost","description":"Lost"},{"deliverableId":"kept","description":"Kept"},{"deliverableId":"downstream-output","description":"Downstream"}]}
                    """);
            if (messages.getFirst().content().equals("numbers prompt"))
                throw new ModelClientException(FailureCategory.MODEL_BAD_REQUEST, false);
            if (messages.getFirst().content().equals("writer prompt")) return new ModelResponse("available result", List.of());
            return new ModelResponse("partial summary", List.of());
        };
        InMemoryRunStore store = new InMemoryRunStore();
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            RunRecord run = workflow(model, tools, workers, executor, store,
                    new CollaborationConfig(5, 2, 10, 5, 0, Duration.ofSeconds(5), Duration.ofSeconds(1)))
                    .runTracked("return what can be done");
            assertEquals(RunStatus.PARTIAL, run.status());
            assertTrue(run.finalAnswer().contains("partial summary"));
            assertTrue(run.finalAnswer().contains("lost"));
            assertTrue(run.finalAnswer().contains("downstream-output"));
            assertEquals(TaskStatus.FAILED, task(run, "bad").status());
            assertEquals(TaskStatus.SUCCEEDED, task(run, "good").status());
            assertEquals(TaskStatus.SKIPPED, task(run, "downstream").status());
        }
    }

    @Test
    void noSuccessfulWorkerResultsFailsWithoutAskingForASummary() {
        ToolRegistry tools = tools();
        WorkerRegistry workers = workers();
        AtomicInteger summaryCalls = new AtomicInteger();
        ModelClient model = (messages, definitions) -> {
            if (isPlannerRequest(messages)) return plan("""
                    {"route":"DELEGATE","tasks":[{"taskId":"bad","workerId":"numbers","description":"must fail","dependencies":[],"deliverableIds":["d"]}],"deliverables":[{"deliverableId":"d","description":"Required"}]}
                    """);
            if (messages.getFirst().content().equals("numbers prompt"))
                throw new ModelClientException(FailureCategory.MODEL_AUTHENTICATION, false);
            summaryCalls.incrementAndGet();
            return new ModelResponse("must not summarize", List.of());
        };
        InMemoryRunStore store = new InMemoryRunStore();
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            RunRecord run = workflow(model, tools, workers, executor, store,
                    new CollaborationConfig(3, 1, 5, 2, 0, Duration.ofSeconds(5), Duration.ofSeconds(1)))
                    .runTracked("nothing succeeded");
            assertEquals(RunStatus.FAILED, run.status());
            assertEquals(FailureCategory.NO_SUCCESSFUL_TASKS, run.failureCategory());
            assertNull(run.finalAnswer());
            assertEquals(0, summaryCalls.get());
        }
    }

    @Test
    void resumesSavedPendingChildTaskWithoutReplanningOrCreatingChildRun() {
        ToolRegistry tools = tools();
        WorkerRegistry workers = workers();
        AtomicInteger requests = new AtomicInteger();
        ModelClient model = (messages, definitions) -> {
            requests.incrementAndGet();
            if (messages.getFirst().content().equals("numbers prompt")) return new ModelResponse("42", List.of());
            return new ModelResponse("resumed summary", List.of());
        };
        InMemoryRunStore store = new InMemoryRunStore();
        RunRecord checkpoint = RunRecord.create("resume-parent", "resume safely", Duration.ofMinutes(1), RunType.COLLABORATIVE);
        checkpoint.transitionTo(RunStatus.RUNNING, java.time.Instant.now(), null);
        checkpoint.setTaskPlan(new TaskPlan(TaskPlan.Route.DELEGATE,
                List.of(new PlannedTask("persisted-task", "numbers", "calculate saved task", List.of(), List.of("answer"))),
                List.of(new TaskPlan.Deliverable("answer", "The answer"))));
        checkpoint.addChildTask(ChildTaskRecord.pending(checkpoint.taskPlan().tasks().getFirst()));
        store.save(checkpoint);
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            RunRecord run = workflow(model, tools, workers, executor, store,
                    new CollaborationConfig(4, 1, 5, 3, 0, Duration.ofSeconds(5), Duration.ofSeconds(1)))
                    .resume("resume-parent");
            assertEquals(RunStatus.SUCCEEDED, run.status());
            assertTrue(run.resumed());
            assertEquals(2, requests.get(), "resume should call the Worker and summarizer, not planner");
            assertEquals(1, store.list().size());
            assertEquals(TaskStatus.SUCCEEDED, task(run, "persisted-task").status());
        }
    }

    @Test
    void totalToolBudgetRejectsWholeBatchBeforeAnyToolExecutes() {
        ToolRegistry tools = tools();
        WorkerRegistry workers = workers();
        AtomicInteger requests = new AtomicInteger();
        ModelClient model = (messages, definitions) -> {
            if (isPlannerRequest(messages)) return plan("""
                    {"route":"DELEGATE","tasks":[{"taskId":"t","workerId":"numbers","description":"calculate","dependencies":[],"deliverableIds":["d"]}],"deliverables":[{"deliverableId":"d","description":"result"}]}
                    """);
            return new ModelResponse("", List.of(new ToolCall("denied-call", "calculator",
                    JSON.createObjectNode().put("operation", "add").put("a", 1).put("b", 2))));
        };
        InMemoryRunStore store = new InMemoryRunStore();
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            CollaborativeWorkflow workflow = workflow(model, tools, workers, executor, store,
                    new CollaborationConfig(3, 1, 5, 0, 0, Duration.ofSeconds(5), Duration.ofSeconds(1)));
            RunRecord run = workflow.runTracked("no tool budget");
            assertEquals(RunStatus.FAILED, run.status());
            assertEquals(FailureCategory.TOOL_BUDGET_EXCEEDED, run.failureCategory());
            assertEquals(0, run.toolCalls());
            assertEquals(0, ((TestTool) tools.find("calculator").orElseThrow()).executions.get());
            assertTrue(run.steps().stream().anyMatch(step -> step.kind() == StepKind.TOOL
                    && step.failureCategory() == FailureCategory.TOOL_BUDGET_EXCEEDED));
        }
    }

    @Test
    void workerToolBudgetRejectsItsToolBatchBeforeExecution() {
        ToolRegistry tools = tools();
        WorkerRegistry workers = new WorkerRegistry();
        workers.register(new WorkerSpec("numbers", "Calculate values", "numbers prompt",
                List.of("calculator"), 2, 0));
        ModelClient model = (messages, definitions) -> {
            if (isPlannerRequest(messages)) return plan("""
                    {"route":"DELEGATE","tasks":[{"taskId":"t","workerId":"numbers","description":"calculate","dependencies":[],"deliverableIds":["d"]}],"deliverables":[{"deliverableId":"d","description":"result"}]}
                    """);
            return new ModelResponse("", List.of(new ToolCall("over-budget", "calculator",
                    JSON.createObjectNode().put("operation", "add").put("a", 1).put("b", 2))));
        };
        InMemoryRunStore store = new InMemoryRunStore();
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            RunRecord run = workflow(model, tools, workers, executor, store,
                    new CollaborationConfig(3, 1, 5, 5, 0, Duration.ofSeconds(5), Duration.ofSeconds(1)))
                    .runTracked("worker tool budget");
            assertEquals(RunStatus.FAILED, run.status());
            assertEquals(TaskStatus.FAILED, task(run, "t").status());
            assertEquals(FailureCategory.WORKER_BUDGET_EXCEEDED, task(run, "t").failureCategory());
            assertEquals(0, run.toolCalls());
            assertEquals(0, ((TestTool) tools.find("calculator").orElseThrow()).executions.get());
        }
    }

    @Test
    void workerModelBudgetStopsBeforeIssuingAnExtraModelRequest() {
        ToolRegistry tools = tools();
        WorkerRegistry workers = new WorkerRegistry();
        workers.register(new WorkerSpec("numbers", "Calculate values", "numbers prompt",
                List.of("calculator"), 1, 2));
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (messages, definitions) -> {
            calls.incrementAndGet();
            if (isPlannerRequest(messages)) return plan("""
                    {"route":"DELEGATE","tasks":[{"taskId":"t","workerId":"numbers","description":"calculate","dependencies":[],"deliverableIds":["d"]}],"deliverables":[{"deliverableId":"d","description":"result"}]}
                    """);
            return new ModelResponse("", List.of(new ToolCall("calc", "calculator",
                    JSON.createObjectNode().put("operation", "add").put("a", 1).put("b", 2))));
        };
        InMemoryRunStore store = new InMemoryRunStore();
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            RunRecord run = workflow(model, tools, workers, executor, store,
                    new CollaborationConfig(3, 1, 5, 5, 0, Duration.ofSeconds(5), Duration.ofSeconds(1)))
                    .runTracked("worker model budget");
            assertEquals(RunStatus.FAILED, run.status());
            assertEquals(TaskStatus.FAILED, task(run, "t").status());
            assertEquals(FailureCategory.WORKER_BUDGET_EXCEEDED, task(run, "t").failureCategory());
            assertEquals(2, calls.get(), "planner and one Worker turn only");
        }
    }

    @Test
    void workerCannotExecuteToolsOutsideItsAllowList() {
        ToolRegistry tools = tools();
        WorkerRegistry workers = workers();
        AtomicInteger request = new AtomicInteger();
        ModelClient model = (messages, definitions) -> {
            if (isPlannerRequest(messages)) return plan("""
                    {"route":"DELEGATE","tasks":[{"taskId":"t","workerId":"numbers","description":"calculate","dependencies":[],"deliverableIds":["d"]}],"deliverables":[{"deliverableId":"d","description":"result"}]}
                    """);
            if (messages.getFirst().content().equals("numbers prompt") && request.getAndIncrement() == 0) {
                assertEquals(List.of("calculator"), definitions.stream().map(d -> d.name()).toList());
                return new ModelResponse("", List.of(new ToolCall("forbidden", "weather", JSON.createObjectNode())));
            }
            if (messages.getFirst().content().equals("numbers prompt")) {
                assertTrue(messages.getLast().content().contains("Tool is not registered"));
                return new ModelResponse("The weather tool was unavailable to this worker.", List.of());
            }
            return new ModelResponse("task done", List.of());
        };
        InMemoryRunStore store = new InMemoryRunStore();
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            RunRecord run = workflow(model, tools, workers, executor, store,
                    new CollaborationConfig(3, 1, 8, 4, 0, Duration.ofSeconds(5), Duration.ofSeconds(1)))
                    .runTracked("restricted tools");
            assertEquals(RunStatus.SUCCEEDED, run.status());
            assertEquals(0, ((TestTool) tools.find("weather").orElseThrow()).executions.get());
            assertEquals(FailureCategory.TOOL_NOT_FOUND,
                    run.steps().stream().filter(step -> step.kind() == StepKind.TOOL).findFirst().orElseThrow().failureCategory());
        }
    }

    @Test
    void multiAgentDeadlineStopsTheRunAndCancelsTheActiveModelFuture() throws Exception {
        ToolRegistry tools = tools();
        WorkerRegistry workers = workers();
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        AtomicInteger interrupted = new AtomicInteger();
        ModelClient model = (messages, definitions) -> {
            if (isPlannerRequest(messages)) return plan("""
                    {"route":"DELEGATE","tasks":[{"taskId":"t","workerId":"numbers","description":"slow","dependencies":[],"deliverableIds":["d"]}],"deliverables":[{"deliverableId":"d","description":"result"}]}
                    """);
            workerStarted.countDown();
            try { Thread.sleep(5_000); }
            catch (InterruptedException cancellation) {
                interrupted.incrementAndGet();
                workerInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return new ModelResponse("late", List.of());
        };
        InMemoryRunStore store = new InMemoryRunStore();
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            RunRecord run = workflow(model, tools, workers, executor, store,
                    new CollaborationConfig(3, 1, 5, 2, 0, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                    .runTracked("deadline");
            assertTrue(workerStarted.await(1, TimeUnit.SECONDS));
            assertEquals(RunStatus.FAILED, run.status());
            assertEquals(FailureCategory.TASK_DEADLINE, run.failureCategory());
            assertTrue(workerInterrupted.await(1, TimeUnit.SECONDS), "Deadline should interrupt the active model call");
            assertEquals(1, interrupted.get());
        }
    }

    @Test
    void cancelRequestInterruptsActiveWorkerAndExecutionThreadWritesCancelledTerminalState() throws Exception {
        ToolRegistry tools = tools();
        WorkerRegistry workers = workers();
        CountDownLatch workerStarted = new CountDownLatch(1);
        AtomicInteger interrupted = new AtomicInteger();
        ModelClient model = (messages, definitions) -> {
            if (isPlannerRequest(messages)) return plan("""
                    {"route":"DELEGATE","tasks":[{"taskId":"t","workerId":"numbers","description":"wait","dependencies":[],"deliverableIds":["d"]}],"deliverables":[{"deliverableId":"d","description":"result"}]}
                    """);
            workerStarted.countDown();
            try { Thread.sleep(5_000); }
            catch (InterruptedException cancellation) { interrupted.incrementAndGet(); Thread.currentThread().interrupt(); }
            return new ModelResponse("late", List.of());
        };
        InMemoryRunStore store = new InMemoryRunStore();
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            CollaborativeWorkflow workflow = workflow(model, tools, workers, executor, store,
                    new CollaborationConfig(3, 1, 5, 2, 0, Duration.ofSeconds(10), Duration.ofSeconds(1)));
            AtomicReference<RunRecord> result = new AtomicReference<>();
            Thread runThread = Thread.ofVirtual().start(() -> result.set(workflow.runTracked("cancel worker")));
            assertTrue(workerStarted.await(2, TimeUnit.SECONDS));
            String runId = store.list().getFirst().runId();
            workflow.cancelRun(runId);
            runThread.join(2_000);
            assertFalse(runThread.isAlive());
            assertEquals(1, interrupted.get());
            assertEquals(RunStatus.CANCELLED, result.get().status());
            assertEquals(RunStatus.CANCELLED, store.find(runId).orElseThrow().status());
        }
    }

    @Test
    void recoveryMarksInProgressToolIndeterminateAndDoesNotReplayIt() {
        WorkerRegistry workers = workers();
        AtomicInteger calculatorExecutions = new AtomicInteger();
        AgentTool countingCalculator = new TestTool("calculator", calculatorExecutions);
        ToolRegistry actualTools = new ToolRegistry();
        actualTools.register(countingCalculator);
        actualTools.register(new TestTool("weather"));
        ModelClient model = (messages, definitions) -> {
            if (messages.stream().anyMatch(message -> message.role() == com.example.agent.model.Message.Role.TOOL
                    && message.content().contains("not replayed"))) return new ModelResponse("handled unknown result", List.of());
            return new ModelResponse("unexpected", List.of());
        };
        InMemoryRunStore store = new InMemoryRunStore();
        RunRecord checkpoint = RunRecord.create("uncertain-parent", "resume", Duration.ofMinutes(1), RunType.COLLABORATIVE);
        checkpoint.transitionTo(RunStatus.RUNNING, java.time.Instant.now(), null);
        PlannedTask planned = new PlannedTask("task", "numbers", "calculate safely", List.of(), List.of("d"));
        checkpoint.setTaskPlan(new TaskPlan(TaskPlan.Route.DELEGATE, List.of(planned),
                List.of(new TaskPlan.Deliverable("d", "Result"))));
        ChildTaskRecord child = ChildTaskRecord.pending(planned).start(List.of(
                com.example.agent.model.Message.system("numbers prompt"),
                com.example.agent.model.Message.user("task context"),
                com.example.agent.model.Message.assistantToolCalls(List.of(new ToolCall("external", "calculator",
                        JSON.createObjectNode().put("operation", "add").put("a", 1).put("b", 2))))),
                java.time.Instant.now()).addModelCall().addToolCalls(1);
        checkpoint.addChildTask(child);
        checkpoint.setModelCalls(1);
        checkpoint.setToolCalls(1);
        checkpoint.addStep(RunStep.workflow("worker-step", StepKind.WORKER, "task", "numbers", "WORKER", java.time.Instant.now()));
        checkpoint.addStep(RunStep.toolPending("tool-step", "external", "calculator", java.time.Instant.now(), "task", "numbers")
                .start(java.time.Instant.now()));
        store.save(checkpoint);

        try (ToolExecutor executor = new ToolExecutor(actualTools, Duration.ofSeconds(1))) {
            RunRecord run = workflow(model, actualTools, workers, executor, store,
                    new CollaborationConfig(3, 1, 8, 4, 0, Duration.ofSeconds(5), Duration.ofSeconds(1)))
                    .resume("uncertain-parent");
            assertEquals(RunStatus.SUCCEEDED, run.status());
            assertEquals(0, calculatorExecutions.get());
            assertEquals(RunStepStatus.INDETERMINATE, run.step("tool-step").status());
            assertTrue(task(run, "task").messages().stream().anyMatch(message -> message.role()
                    == com.example.agent.model.Message.Role.TOOL && message.content().contains("not replayed")));
        }
    }

    @Test
    void recoveryExecutesPersistedPendingToolOnceAndThenContinuesWorker() {
        WorkerRegistry workers = workers();
        AtomicInteger calculatorExecutions = new AtomicInteger();
        ToolRegistry actualTools = new ToolRegistry();
        actualTools.register(new TestTool("calculator", calculatorExecutions));
        actualTools.register(new TestTool("weather"));
        ModelClient model = (messages, definitions) -> {
            if (messages.stream().anyMatch(message -> message.role() == com.example.agent.model.Message.Role.TOOL))
                return new ModelResponse("continued after tool", List.of());
            return new ModelResponse("unexpected", List.of());
        };
        InMemoryRunStore store = new InMemoryRunStore();
        RunRecord checkpoint = RunRecord.create("pending-tool-parent", "resume pending tool", Duration.ofMinutes(1), RunType.COLLABORATIVE);
        checkpoint.transitionTo(RunStatus.RUNNING, java.time.Instant.now(), null);
        PlannedTask planned = new PlannedTask("task", "numbers", "calculate", List.of(), List.of("d"));
        ToolCall call = new ToolCall("pending-call", "calculator",
                JSON.createObjectNode().put("operation", "add").put("a", 1).put("b", 2));
        ChildTaskRecord child = ChildTaskRecord.pending(planned).start(List.of(
                com.example.agent.model.Message.system("numbers prompt"),
                com.example.agent.model.Message.user("task context"),
                com.example.agent.model.Message.assistantToolCalls(List.of(call))), java.time.Instant.now())
                .addModelCall().addToolCalls(1);
        checkpoint.addChildTask(child);
        checkpoint.setTaskPlan(new TaskPlan(TaskPlan.Route.DELEGATE, List.of(planned),
                List.of(new TaskPlan.Deliverable("d", "Result"))));
        checkpoint.setModelCalls(1);
        checkpoint.setToolCalls(1); // The call was budget-reserved before its PENDING checkpoint.
        checkpoint.setBudgetReservations(java.util.Map.of("tool-pending", com.example.agent.workflow.BudgetKind.TOOL_CALL));
        checkpoint.addStep(RunStep.workflow("worker-pending", StepKind.WORKER, "task", "numbers", "WORKER", java.time.Instant.now()));
        checkpoint.addStep(RunStep.toolPending("tool-pending", call.id(), call.name(), java.time.Instant.now(), "task", "numbers"));
        store.save(checkpoint);

        try (ToolExecutor executor = new ToolExecutor(actualTools, Duration.ofSeconds(1))) {
            RunRecord run = workflow(model, actualTools, workers, executor, store,
                    new CollaborationConfig(3, 1, 8, 4, 0, Duration.ofSeconds(5), Duration.ofSeconds(1)))
                    .resume("pending-tool-parent");
            assertEquals(RunStatus.SUCCEEDED, run.status());
            assertEquals(1, calculatorExecutions.get());
            assertEquals(RunStepStatus.SUCCEEDED, run.step("tool-pending").status());
            assertEquals(1, run.toolCalls());
        }
    }

    @Test
    void deterministicComparisonShowsMultiAgentPlanningAndSummaryCallsCostMore() throws Exception {
        ToolRegistry singleTools = new ToolRegistry();
        singleTools.register(new com.example.agent.tools.CalculatorTool());
        ModelClient singleModel = new QueueModel(List.of(
                new ModelResponse("", List.of(new ToolCall("single", "calculator",
                        JSON.createObjectNode().put("operation", "multiply").put("a", 6).put("b", 7)))),
                new ModelResponse("42", List.of())));
        RunRecord baseline;
        try (ToolExecutor executor = new ToolExecutor(singleTools, Duration.ofSeconds(1))) {
            baseline = new AgentRuntime(singleModel, singleTools, executor,
                    new InMemoryRunStore(), new RuntimeConfig(5, 5, 0, Duration.ofSeconds(5)))
                    .runTracked("6 times 7");
        }

        ToolRegistry multiTools = new ToolRegistry();
        multiTools.register(new com.example.agent.tools.CalculatorTool());
        multiTools.register(new com.example.agent.tools.WeatherTool());
        WorkerRegistry multiWorkers = new WorkerRegistry();
        multiWorkers.register(new WorkerSpec("numbers", "calculate", "numbers prompt", List.of("calculator"), 3, 3));
        ModelClient multiModel = new QueueModel(List.of(
                plan("""
                        {"route":"DELEGATE","tasks":[{"taskId":"calc","workerId":"numbers","description":"calculate 6 times 7","dependencies":[],"deliverableIds":["answer"]}],"deliverables":[{"deliverableId":"answer","description":"calculation"}]}
                        """),
                new ModelResponse("", List.of(new ToolCall("multi", "calculator",
                        JSON.createObjectNode().put("operation", "multiply").put("a", 6).put("b", 7)))),
                new ModelResponse("42", List.of()),
                new ModelResponse("42", List.of())));
        RunRecord multi;
        InMemoryRunStore store = new InMemoryRunStore();
        try (ToolExecutor executor = new ToolExecutor(multiTools, Duration.ofSeconds(1))) {
            multi = workflow(multiModel, multiTools, multiWorkers, executor, store,
                    new CollaborationConfig(3, 1, 8, 5, 0, Duration.ofSeconds(5), Duration.ofSeconds(1)))
                    .runTracked("6 times 7");
        }
        assertEquals(RunStatus.SUCCEEDED, baseline.status());
        assertEquals(RunStatus.SUCCEEDED, multi.status());
        assertEquals("42", baseline.finalAnswer());
        assertEquals(baseline.finalAnswer(), multi.finalAnswer());
        assertEquals(2, baseline.modelCalls());
        assertEquals(4, multi.modelCalls());
        assertTrue(baseline.totalDurationMillis() >= 0 && multi.totalDurationMillis() >= 0);
    }

    @Test
    void existingAgentRuntimeStillTreatsPartialAsTerminalOnlyForCollaboration() {
        // Compile-time/runtime guard: adding PARTIAL does not alter the legacy single-agent constructor API.
        assertNotNull(AgentRuntime.class);
        RunRecord run = RunRecord.create("status-test", "x", Duration.ofSeconds(1));
        run.transitionTo(RunStatus.RUNNING, java.time.Instant.now(), null);
        run.transitionTo(RunStatus.PARTIAL, java.time.Instant.now(), FailureCategory.WORKER_FAILED);
        assertEquals(RunStatus.PARTIAL, run.status());
        assertThrows(IllegalStateException.class,
                () -> run.transitionTo(RunStatus.SUCCEEDED, java.time.Instant.now(), null));
    }

    @Test
    void supervisorRenewsItsShortLeaseDuringLongPlanningCall() throws Exception {
        ToolRegistry tools = tools();
        WorkerRegistry workers = workers();
        InMemoryRunStore store = new InMemoryRunStore();
        AtomicInteger takeovers = new AtomicInteger();
        ModelClient slowPlanner = (messages, definitions) -> {
            if (isPlannerRequest(messages)) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("planner interrupted", interrupted);
                }
                String runId = store.list().stream().filter(run -> run.status() == RunStatus.RUNNING)
                        .map(RunRecord::runId).findFirst().orElseThrow();
                if (store.claim(runId, "competing-supervisor", java.time.Instant.now(), Duration.ofSeconds(1)).isPresent()) {
                    takeovers.incrementAndGet();
                }
                return plan("{\"route\":\"NO_SPLIT\",\"tasks\":[],\"deliverables\":[]}");
            }
            return new ModelResponse("完成", List.of());
        };
        var config = new CollaborationConfig(3, 1, 5, 2, 0,
                Duration.ofSeconds(3), Duration.ofSeconds(1));
        RunLeasePolicy leasePolicy = new RunLeasePolicy(Duration.ofMillis(180), Duration.ofMillis(30));
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            var workflow = new CollaborativeWorkflow(slowPlanner, tools, workers, store, config, JSON,
                    com.example.agent.observability.AgentTelemetry.noop(), leasePolicy);
            assertEquals(RunStatus.SUCCEEDED, workflow.runTracked("long planning request").status());
        }
        assertEquals(0, takeovers.get(), "the active Supervisor must keep its parent Run lease");
    }

    private static CollaborativeWorkflow workflow(ModelClient model, ToolRegistry tools, WorkerRegistry workers,
                                                  ToolExecutor executor, InMemoryRunStore store,
                                                  CollaborationConfig config) {
        return new CollaborativeWorkflow(model, tools, workers, store, config, JSON);
    }

    private static ModelResponse plan(String json) { return new ModelResponse(json, List.of()); }
    private static boolean isPlannerRequest(List<com.example.agent.model.Message> messages) {
        return messages.size() > 1 && messages.getLast().content().contains("Registered workers");
    }
    private static ChildTaskRecord task(RunRecord run, String id) {
        return run.childTasks().stream().filter(task -> task.taskId().equals(id)).findFirst().orElseThrow();
    }

    private static WorkerRegistry workers() {
        WorkerRegistry registry = new WorkerRegistry();
        registry.register(new WorkerSpec("numbers", "Calculate values", "numbers prompt", List.of("calculator"), 2, 3));
        registry.register(new WorkerSpec("writer", "Explain supplied facts", "writer prompt", List.of("weather"), 2, 3));
        return registry;
    }

    private static ToolRegistry tools() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new TestTool("calculator"));
        registry.register(new TestTool("weather"));
        return registry;
    }

    private static final class TestTool implements AgentTool {
        private final String name;
        private final AtomicInteger executions;
        private TestTool(String name) { this(name, new AtomicInteger()); }
        private TestTool(String name, AtomicInteger executions) { this.name = name; this.executions = executions; }
        @Override public String name() { return name; }
        @Override public String description() { return "test tool " + name; }
        @Override public JsonNode inputSchema() {
            JsonNode properties = JSON.createObjectNode();
            if (name.equals("calculator")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) properties).set("operation", JSON.createObjectNode().put("type", "string"));
                ((com.fasterxml.jackson.databind.node.ObjectNode) properties).set("a", JSON.createObjectNode().put("type", "number"));
                ((com.fasterxml.jackson.databind.node.ObjectNode) properties).set("b", JSON.createObjectNode().put("type", "number"));
            }
            return JSON.createObjectNode().put("type", "object").set("properties", properties);
        }
        @Override public JsonNode execute(JsonNode arguments) { executions.incrementAndGet(); return JSON.createObjectNode().put("result", 42); }
    }

    private static final class QueueModel implements ModelClient {
        private final java.util.Queue<ModelResponse> responses = new java.util.concurrent.ConcurrentLinkedQueue<>();
        private QueueModel(List<ModelResponse> responses) { this.responses.addAll(responses); }
        @Override public ModelResponse complete(List<com.example.agent.model.Message> messages,
                                                List<com.example.agent.model.ToolDefinition> definitions) {
            ModelResponse response = responses.poll();
            if (response == null) throw new AssertionError("Unexpected model request");
            return response;
        }
    }
}
