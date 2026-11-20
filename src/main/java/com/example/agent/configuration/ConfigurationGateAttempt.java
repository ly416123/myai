package com.example.agent.configuration;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** Append-only record of a candidate evaluation decision, including rejected decisions without a Release. */
public record ConfigurationGateAttempt(long attemptId, String revisionId, String evaluationId,
                                       String baselineReleaseId, JsonNode decision, String actor,
                                       Instant occurredAt) {
    public ConfigurationGateAttempt {
        if (revisionId == null || evaluationId == null || actor == null || actor.isBlank()
                || decision == null || !decision.isObject() || occurredAt == null)
            throw new IllegalArgumentException("Configuration gate attempt is invalid");
        decision = decision.deepCopy();
    }

    @Override public JsonNode decision() { return decision.deepCopy(); }
}
