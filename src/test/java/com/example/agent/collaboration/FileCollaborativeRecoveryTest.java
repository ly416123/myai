package com.example.agent.collaboration;

import com.example.agent.client.ModelClient;
import com.example.agent.model.ModelResponse;
import com.example.agent.tool.AgentTool;
import com.example.agent.tool.ToolRegistry;
import com.example.agent.workflow.JsonFileRunStore;
import com.example.agent.workflow.RunRecord;
import com.example.agent.workflow.RunStatus;
import com.example.agent.workflow.RunStep;
import com.example.agent.workflow.RunStepStatus;
import com.example.agent.workflow.StepKind;
import com.example.agent.workflow.RunType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FileCollaborativeRecoveryTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    @TempDir Path directory;

    @Test
    void parentJsonCheckpointRestoresTaskIdsWorkerStepsAndPendingTaskAcrossStoreInstances() {
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient model = (messages, tools) -> {
            modelCalls.incrementAndGet();
            return new ModelResponse(messages.getFirst().content().equals("worker prompt")
                    ? "file-backed result" : "file-backed summary", List.of());
        };
        ToolRegistry tools = new ToolRegistry();
        tools.register(new CounterTool());
        WorkerRegistry workers = new WorkerRegistry();
        workers.register(new WorkerSpec("counter", "Count", "worker prompt", List.of("counter"), 2, 2));
        String runId = "file-collaboration";
        RunRecord checkpoint = RunRecord.create(runId, "resume a saved task", Duration.ofMinutes(1), RunType.COLLABORATIVE);
        checkpoint.transitionTo(RunStatus.RUNNING, Instant.now(), null);
        PlannedTask planned = new PlannedTask("task-1", "counter", "return the saved value", List.of(), List.of("value"));
        checkpoint.setTaskPlan(new TaskPlan(TaskPlan.Route.DELEGATE, List.of(planned),
                List.of(new TaskPlan.Deliverable("value", "The value"))));
        checkpoint.addChildTask(ChildTaskRecord.pending(planned));
        checkpoint.addStep(RunStep.workflow("planner-step", StepKind.PLAN, null, "supervisor", "PLAN", Instant.now())
                .finish(RunStepStatus.SUCCEEDED, Instant.now(), null, null, null));
        JsonFileRunStore beforeRestart = new JsonFileRunStore(directory, JSON);
        beforeRestart.save(checkpoint);

        CollaborativeWorkflow workflow = new CollaborativeWorkflow(model, tools, workers,
                new JsonFileRunStore(directory, JSON),
                new CollaborationConfig(3, 1, 5, 2, 0, Duration.ofSeconds(5), Duration.ofSeconds(1)), JSON);
        RunRecord result = workflow.resume(runId);

        assertEquals(RunStatus.SUCCEEDED, result.status());
        assertEquals(RunType.COLLABORATIVE, result.runType());
        assertTrue(result.resumed());
        assertEquals("file-backed summary", result.finalAnswer());
        assertEquals("task-1", result.childTasks().getFirst().taskId());
        assertEquals("counter", result.childTasks().getFirst().workerId());
        assertTrue(result.steps().stream().anyMatch(step -> "task-1".equals(step.taskId())
                && "counter".equals(step.workerId())));
        assertEquals(2, modelCalls.get(), "saved plan skips replanning");
    }

    private static final class CounterTool implements AgentTool {
        @Override public String name() { return "counter"; }
        @Override public String description() { return "deterministic counter tool"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode().put("type", "object").set("properties", JSON.createObjectNode()); }
        @Override public JsonNode execute(JsonNode arguments) { return JSON.createObjectNode().put("count", 1); }
    }
}
