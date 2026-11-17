package com.example.agent.app;

import com.example.agent.memory.MemoryContextSnapshot;
import com.example.agent.memory.MemoryEntry;
import com.example.agent.memory.MemoryKind;
import com.example.agent.workflow.InMemoryRunStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RunSubmissionMemorySnapshotTest {
    @Test
    void pinsMemoryBeforeFirstQueueCheckpointAndDoesNotReResolveOnResumeRead() {
        InMemoryRunStore store = new InMemoryRunStore();
        AtomicInteger resolutions = new AtomicInteger();
        var snapshot = new MemoryContextSnapshot(false, "policy-v1", List.of(new MemoryEntry(
                UUID.randomUUID(), "default", "answer.language", MemoryKind.PREFERENCE, "回答使用中文", 1,
                Instant.parse("2026-11-18T00:00:00Z"), Instant.parse("2026-11-18T00:00:00Z"))), 20);
        var service = new RunSubmissionService(store, Duration.ofMinutes(1), RunSubmissionPolicy.noAuthentication(),
                null, null, null, query -> {
                    resolutions.incrementAndGet();
                    return snapshot;
                });

        var submitted = service.submitSingle("用中文总结");
        var persisted = store.find(submitted.runId()).orElseThrow();

        assertEquals(snapshot, persisted.memoryContextSnapshot());
        assertEquals(1, resolutions.get());
        assertEquals(snapshot, persisted.memoryContextOrEmpty());
        assertEquals(1, resolutions.get(), "reading a checkpoint must not query the memory store again");
    }
}
