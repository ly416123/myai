package com.example.agent.memory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Current, versioned memory content. It must never be rendered into ordinary logs. */
public record MemoryEntry(UUID memoryId, String namespace, String key, MemoryKind kind, String content,
                          long version, Instant createdAt, Instant updatedAt) {
    public MemoryEntry {
        Objects.requireNonNull(memoryId, "memoryId");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 1) throw new IllegalArgumentException("Memory version must be positive");
    }
}
