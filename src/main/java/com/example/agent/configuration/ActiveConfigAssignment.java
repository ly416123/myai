package com.example.agent.configuration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Versioned environment/agent pointer; the active and candidate releases are immutable. */
public record ActiveConfigAssignment(String environment, String agentId, String activeReleaseId,
                                     String candidateReleaseId, int candidatePercent, long version,
                                     Instant updatedAt) {
    public ActiveConfigAssignment {
        require(environment, "Environment");
        require(agentId, "Agent ID");
        require(activeReleaseId, "Active release ID");
        if (candidateReleaseId == null && candidatePercent != 0
                || candidateReleaseId != null && (candidatePercent < 1 || candidatePercent > 99)) {
            throw new IllegalArgumentException("Candidate release percentage is invalid");
        }
        if (version < 1 || updatedAt == null) throw new IllegalArgumentException("Assignment version and time are required");
        updatedAt = updatedAt.truncatedTo(ChronoUnit.MICROS);
    }

    private static void require(String value, String name) {
        if (value == null || !value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
