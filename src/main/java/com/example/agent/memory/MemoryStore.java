package com.example.agent.memory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for current memory entries and metadata-only change events. */
public interface MemoryStore {
    MemoryEntry create(String namespace, String key, MemoryKind kind, String content, int namespaceLimit);
    default MemoryEntry create(String namespace, String key, MemoryKind kind, String content) {
        return create(namespace, key, kind, content, 500);
    }
    Optional<MemoryEntry> update(String namespace, UUID memoryId, long expectedVersion,
                                MemoryKind kind, String content);
    boolean delete(String namespace, UUID memoryId, long expectedVersion);
    Optional<MemoryEntry> findByKey(String namespace, String key);
    List<MemoryEntry> listActive(String namespace, int limit);
    int countActive(String namespace);
    List<MemoryEvent> events(UUID memoryId);
}
