package com.example.agent.memory;

import java.time.Instant;
import java.util.UUID;

/** Metadata-only history; deliberately has no field for memory keys or content. */
public record MemoryEvent(long eventId, UUID memoryId, String operation, long version,
                          Instant occurredAt, String source) { }
