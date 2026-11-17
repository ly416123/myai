package com.example.agent.configuration;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** An immutable publication record linking a revision to evaluation evidence and provenance. */
public record AgentConfigRelease(String releaseId, String revisionId, String contentHash,
                                 String evaluationId, String baselineReleaseId, JsonNode gateDecision,
                                 String sourceCommit, String releaseNotes, String createdBy, Instant createdAt) {
    public AgentConfigRelease {
        require(releaseId, "Release ID");
        require(revisionId, "Revision ID");
        if (contentHash == null || !contentHash.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Configuration hash is invalid");
        }
        gateDecision = gateDecision == null ? null : gateDecision.deepCopy();
        if (gateDecision == null || !gateDecision.isObject()) throw new IllegalArgumentException("Gate decision is required");
        if (sourceCommit != null && sourceCommit.length() > 80) throw new IllegalArgumentException("Source commit is too long");
        if (releaseNotes == null || releaseNotes.length() > 2000) throw new IllegalArgumentException("Release notes are invalid");
        require(createdBy, "Release actor");
        if (createdAt == null) throw new IllegalArgumentException("Release time is required");
        createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
    }

    @Override public JsonNode gateDecision() { return gateDecision.deepCopy(); }

    private static void require(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 120) throw new IllegalArgumentException(name + " is invalid");
    }
}
