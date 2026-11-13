package com.example.agent.workflow;

import com.example.agent.client.ModelClient;
import com.example.agent.collaboration.CollaborationConfig;
import com.example.agent.collaboration.CollaborativeWorkflow;
import com.example.agent.collaboration.WorkerRegistry;
import com.example.agent.runtime.AgentRuntime;
import com.example.agent.tool.ToolExecutor;
import com.example.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RunTypeRecoveryTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test
    void singleAgentEntryPointRejectsCollaborativeCheckpointBeforeChangingIt() {
        InMemoryRunStore store = checkpoint(RunType.COLLABORATIVE);
        ToolRegistry tools = new ToolRegistry();
        ModelClient forbidden = (messages, definitions) -> fail("wrong recovery entry point called the model");
        try (ToolExecutor executor = new ToolExecutor(tools, Duration.ofSeconds(1))) {
            AgentRuntime runtime = new AgentRuntime(forbidden, tools, executor, store,
                    new RuntimeConfig(5, 5, 0, Duration.ofSeconds(5)));

            assertThrows(IllegalStateException.class, () -> runtime.resume("cross-type-run"));
            RunRecord unchanged = store.find("cross-type-run").orElseThrow();
            assertEquals(RunType.COLLABORATIVE, unchanged.runType());
            assertEquals(RunStatus.RUNNING, unchanged.status());
            assertFalse(unchanged.resumed());
            assertEquals(0, unchanged.modelCalls());
        }
    }

    @Test
    void collaborativeEntryPointRejectsSingleAgentCheckpointBeforePlanning() {
        InMemoryRunStore store = checkpoint(RunType.SINGLE_AGENT);
        ToolRegistry tools = new ToolRegistry();
        WorkerRegistry workers = new WorkerRegistry();
        ModelClient forbidden = (messages, definitions) -> fail("wrong recovery entry point called the planner");
        CollaborativeWorkflow workflow = new CollaborativeWorkflow(forbidden, tools, workers, store,
                new CollaborationConfig(3, 1, 5, 5, 0, Duration.ofSeconds(5), Duration.ofSeconds(1)), JSON);

        assertThrows(IllegalStateException.class, () -> workflow.resume("cross-type-run"));
        RunRecord unchanged = store.find("cross-type-run").orElseThrow();
        assertEquals(RunType.SINGLE_AGENT, unchanged.runType());
        assertEquals(RunStatus.RUNNING, unchanged.status());
        assertFalse(unchanged.resumed());
        assertEquals(0, unchanged.modelCalls());
        assertNull(unchanged.taskPlan());
    }

    private static InMemoryRunStore checkpoint(RunType type) {
        InMemoryRunStore store = new InMemoryRunStore();
        RunRecord run = RunRecord.create("cross-type-run", "saved request", Duration.ofMinutes(1), type);
        run.transitionTo(RunStatus.RUNNING, java.time.Instant.now(), null);
        store.save(run);
        return store;
    }
}
