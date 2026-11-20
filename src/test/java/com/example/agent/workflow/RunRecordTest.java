package com.example.agent.workflow;

import com.example.agent.memory.MemoryContextSnapshot;
import com.example.agent.memory.MemoryEntry;
import com.example.agent.memory.MemoryKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RunRecordTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void roundTripsPinnedMemorySnapshotAndTreatsMissingSnapshotAsEmptyOnResume() throws Exception {
        RunRecord run = RunRecord.create("run-memory-1", "用中文总结", Duration.ofMinutes(1));
        var entry = new MemoryEntry(UUID.randomUUID(), "default", "answer.language", MemoryKind.PREFERENCE,
                "回答使用中文", 1, Instant.parse("2026-11-18T00:00:00Z"), Instant.parse("2026-11-18T00:00:00Z"));
        run.setMemoryContextSnapshot(new MemoryContextSnapshot(false, "policy-v1", List.of(entry), 20));

        RunRecord restored = mapper.readValue(mapper.writeValueAsBytes(run), RunRecord.class);
        assertEquals(run.memoryContextSnapshot(), restored.memoryContextSnapshot());

        var oldPayload = mapper.readTree(mapper.writeValueAsBytes(run));
        ((com.fasterxml.jackson.databind.node.ObjectNode) oldPayload).remove("memoryContextSnapshot");
        RunRecord oldRun = mapper.treeToValue(oldPayload, RunRecord.class);
        assertNull(oldRun.memoryContextSnapshot());
        assertEquals(MemoryContextSnapshot.empty(), oldRun.memoryContextOrEmpty());
    }
}
