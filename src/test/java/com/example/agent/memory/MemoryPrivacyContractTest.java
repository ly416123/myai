package com.example.agent.memory;

import com.example.agent.observability.AgentTelemetry;
import com.example.agent.workflow.InMemoryRunStore;
import com.example.agent.workflow.RunRecord;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class MemoryPrivacyContractTest {
    @Test
    void deleteHidesMemoryFromFutureRunsButExistingRunSnapshotRemainsAndSignalsStayMetadataOnly() {
        var meters = new SimpleMeterRegistry();
        var telemetry = new AgentTelemetry(meters, ObservationRegistry.NOOP);
        var store = new InMemoryMemoryStore();
        var service = new MemoryService(store, new MemoryRetriever(5, 2_000), "private-namespace", 10, telemetry);
        var entry = service.remember("private.key", MemoryKind.FACT, "private-content-9f11");
        var existingSnapshot = service.snapshot("private-content-9f11");
        var runStore = new InMemoryRunStore();
        var run = RunRecord.create("memory-run", "continue", Duration.ofMinutes(1));
        run.setMemoryContextSnapshot(existingSnapshot);
        runStore.save(run);

        service.delete("private.key");
        var futureSnapshot = service.snapshot("private-content-9f11");
        var savedRun = runStore.find(run.runId()).orElseThrow();
        String signals = meters.getMeters().stream().map(meter -> meter.getId().toString())
                .reduce("", (left, right) -> left + " " + right)
                + store.events(entry.memoryId());

        assertTrue(futureSnapshot.entries().isEmpty());
        assertEquals("private-content-9f11", savedRun.memoryContextOrEmpty().entries().getFirst().content());
        assertFalse(signals.contains("private-namespace"));
        assertFalse(signals.contains("private.key"));
        assertFalse(signals.contains("private-content-9f11"));
        assertFalse(signals.contains("private-content"));
    }
}
