package com.example.agent.configuration;

import java.time.Instant;

/** Append-only, redacted audit event for release lifecycle operations. */
public record ReleaseEvent(long eventId, String environment, String agentId, ReleaseAction action,
                           String releaseId, String actor, String reason, Instant occurredAt) { }
