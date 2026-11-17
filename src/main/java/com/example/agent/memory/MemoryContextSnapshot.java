package com.example.agent.memory;

import java.util.List;
import java.util.Objects;

/** Immutable retrieval result pinned to a Run before its first checkpoint is saved. */
public record MemoryContextSnapshot(boolean degraded, String policyVersion,
                                    List<MemoryEntry> entries, int characterCount) {
    public MemoryContextSnapshot {
        Objects.requireNonNull(policyVersion, "policyVersion");
        entries = List.copyOf(entries == null ? List.of() : entries);
        if (characterCount < 0) throw new IllegalArgumentException("characterCount must not be negative");
    }

    public static MemoryContextSnapshot empty() {
        return new MemoryContextSnapshot(false, "policy-v1", List.of(), 0);
    }

    public static MemoryContextSnapshot degradedEmpty() {
        return new MemoryContextSnapshot(true, "policy-v1", List.of(), 0);
    }
}
