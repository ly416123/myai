package com.example.agent.workflow;

import com.example.agent.model.Message;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunRetentionServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");

    @Test
    void disabledRetentionNeverDeletesRuns() {
        InMemoryRunStore store = new InMemoryRunStore();
        store.save(terminalRun("retention-disabled", NOW.minus(Duration.ofDays(60))));

        RunRetentionService retention = new RunRetentionService(store, Duration.ZERO, 100,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(0, retention.runOnce());
        assertTrue(store.find("retention-disabled").isPresent());
    }

    @Test
    void removesOnlyTerminalRunsOlderThanConfiguredCutoff() {
        InMemoryRunStore store = new InMemoryRunStore();
        store.save(terminalRun("retention-expired", NOW.minus(Duration.ofDays(31))));
        store.save(terminalRun("retention-boundary", NOW.minus(Duration.ofDays(30))));
        RunRecord active = RunRecord.create("retention-active", "active", Duration.ofDays(40));
        active.transitionTo(RunStatus.RUNNING, NOW.minus(Duration.ofDays(31)), null);
        active.addMessage(Message.user("active"));
        store.save(active);

        RunRetentionService retention = new RunRetentionService(store, Duration.ofDays(30), 100,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(1, retention.runOnce());
        assertTrue(store.find("retention-expired").isEmpty());
        assertTrue(store.find("retention-boundary").isPresent());
        assertTrue(store.find("retention-active").isPresent());
    }

    private static RunRecord terminalRun(String runId, Instant completedAt) {
        RunRecord run = RunRecord.create(runId, "terminal", Duration.ofDays(90));
        run.transitionTo(RunStatus.RUNNING, completedAt.minusSeconds(1), null);
        run.transitionTo(RunStatus.SUCCEEDED, completedAt, null);
        return run;
    }
}
