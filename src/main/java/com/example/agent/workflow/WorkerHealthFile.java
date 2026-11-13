package com.example.agent.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Publishes a small local readiness heartbeat without opening an HTTP management port. */
public final class WorkerHealthFile {
    public static final Path DEFAULT_PATH = Path.of(System.getProperty("java.io.tmpdir"), "myai-worker-health");
    public static final Path DEFAULT_LIVENESS_PATH = Path.of(System.getProperty("java.io.tmpdir"), "myai-worker-live");

    private final Path marker;
    private final Clock clock;

    public WorkerHealthFile(Path marker) {
        this(marker, Clock.systemUTC());
    }

    WorkerHealthFile(Path marker, Clock clock) {
        if (marker == null || clock == null) throw new IllegalArgumentException("Health marker configuration is required");
        this.marker = marker;
        this.clock = clock;
    }

    /** Writes atomically so a probe never observes a partially written timestamp. */
    public void recordHeartbeat() {
        Path parent = marker.toAbsolutePath().getParent();
        Path temporary = marker.resolveSibling(marker.getFileName() + ".tmp");
        try {
            Files.createDirectories(parent);
            Files.writeString(temporary, Long.toString(clock.instant().getEpochSecond()));
            try {
                Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            throw new IllegalStateException("Unable to update worker readiness marker");
        }
    }

    public boolean isFresh(Duration maximumAge) {
        if (maximumAge == null || maximumAge.isNegative() || maximumAge.isZero()) return false;
        try {
            long epochSecond = Long.parseLong(Files.readString(marker).trim());
            Instant recorded = Instant.ofEpochSecond(epochSecond);
            Duration age = Duration.between(recorded, clock.instant());
            return !age.isNegative() && age.compareTo(maximumAge) <= 0;
        } catch (IOException | NumberFormatException | java.time.DateTimeException failure) {
            return false;
        }
    }
}
