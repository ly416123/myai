package com.example.agent.workflow;

import com.example.agent.model.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonFileRunStoreTest {
    @TempDir Path directory;

    @Test
    void writesPrivateCheckpointAndReloadsRunState() {
        JsonFileRunStore store = new JsonFileRunStore(directory, new ObjectMapper().findAndRegisterModules());
        RunRecord run = RunRecord.create("run-123", "hello", Duration.ofMinutes(2));
        run.setStatus(RunStatus.RUNNING);
        run.addMessage(Message.user("hello"));
        run.setModelCalls(2);
        store.save(run);

        RunRecord loaded = store.find("run-123").orElseThrow();
        assertEquals(RunStatus.RUNNING, loaded.status());
        assertEquals(2, loaded.modelCalls());
        assertEquals("hello", loaded.messages().getFirst().content());
        assertTrue(store.list().stream().anyMatch(item -> item.runId().equals("run-123")));
    }

    @Test
    void recentListingReturnsNewestRunsFirstAndHonorsLimit() {
        JsonFileRunStore store = new JsonFileRunStore(directory, new ObjectMapper().findAndRegisterModules());
        Instant base = Instant.parse("2026-11-12T00:00:00Z");
        for (int i = 1; i <= 3; i++) {
            RunRecord run = RunRecord.create("json-recent-" + i, "task", Duration.ofMinutes(1));
            run.setCreatedAt(base.plusSeconds(i));
            store.save(run);
        }

        assertEquals(List.of("json-recent-3", "json-recent-2"), store.listRecent(2).stream()
                .map(RunRecord::runId).toList());
    }

    @Test
    void rejectsPathTraversalRunIds() {
        JsonFileRunStore store = new JsonFileRunStore(directory, new ObjectMapper().findAndRegisterModules());
        assertThrows(IllegalArgumentException.class, () -> store.find("../secret"));
    }

    @Test
    void readsPhaseTwoCheckpointWithoutNewCollaborationFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RunRecord phaseTwo = RunRecord.create("legacy-run", "legacy input", Duration.ofMinutes(1));
        var oldJson = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(phaseTwo);
        oldJson.remove(List.of("taskPlan", "childTasks", "agentMessages", "runType"));
        var oldSteps = mapper.createArrayNode();
        oldSteps.add(mapper.createObjectNode().put("stepId", "old-model-step").put("kind", "MODEL")
                .put("status", "SUCCEEDED").put("startedAt", "2026-01-01T00:00:00Z")
                .put("completedAt", "2026-01-01T00:00:01Z").put("durationMillis", 1000));
        oldJson.set("steps", oldSteps);

        RunRecord loaded = mapper.treeToValue(oldJson, RunRecord.class);

        assertEquals("legacy-run", loaded.runId());
        assertEquals(RunStatus.CREATED, loaded.status());
        assertTrue(loaded.childTasks().isEmpty());
        assertTrue(loaded.agentMessages().isEmpty());
        assertNull(loaded.taskPlan());
        assertEquals(RunType.SINGLE_AGENT, loaded.runType(), "legacy checkpoints without a runType remain single-agent runs");
        assertEquals("old-model-step", loaded.steps().getFirst().stepId());
        assertNull(loaded.steps().getFirst().taskId());
    }
}
