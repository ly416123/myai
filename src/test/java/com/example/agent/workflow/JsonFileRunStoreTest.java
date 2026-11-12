package com.example.agent.workflow;

import com.example.agent.model.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

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
    void rejectsPathTraversalRunIds() {
        JsonFileRunStore store = new JsonFileRunStore(directory, new ObjectMapper().findAndRegisterModules());
        assertThrows(IllegalArgumentException.class, () -> store.find("../secret"));
    }
}
