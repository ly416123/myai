package com.example.agent.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class WorkerHealthFileTest {
    @TempDir
    Path directory;

    @Test
    void successfulPollWritesFreshMarkerAndClockAgesItOut() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC);
        WorkerHealthFile health = new WorkerHealthFile(directory.resolve("ready"), clock);

        assertFalse(health.isFresh(Duration.ofSeconds(15)));
        health.recordHeartbeat();
        assertTrue(health.isFresh(Duration.ofSeconds(15)));

        WorkerHealthFile later = new WorkerHealthFile(directory.resolve("ready"),
                Clock.offset(clock, Duration.ofSeconds(16)));
        assertFalse(later.isFresh(Duration.ofSeconds(15)));
    }

    @Test
    void futureAndInvalidMarkerValuesAreNotHealthy() throws Exception {
        Path marker = directory.resolve("ready");
        java.nio.file.Files.writeString(marker, "not-an-epoch");
        assertFalse(new WorkerHealthFile(marker).isFresh(Duration.ofSeconds(15)));
        java.nio.file.Files.writeString(marker, Long.toString(Instant.now().plusSeconds(10).getEpochSecond()));
        assertFalse(new WorkerHealthFile(marker).isFresh(Duration.ofSeconds(15)));
    }
}
