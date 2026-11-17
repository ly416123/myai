package com.example.agent.memory;

import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Deterministic, process-local Fake store for service and retrieval tests. */
public final class InMemoryMemoryStore implements MemoryStore {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<UUID, StoredMemory> entries = new HashMap<>();
    private final List<MemoryEvent> events = new ArrayList<>();
    private long nextEventId = 1;
    private volatile boolean failReads;

    @Override
    public MemoryEntry create(String namespace, String key, MemoryKind kind, String content) {
        lock.writeLock().lock();
        try {
            ensureReadable();
            if (activeByKey(namespace, key).isPresent()) throw new DuplicateKeyException("Memory key already exists");
            Instant now = Instant.now();
            MemoryEntry entry = new MemoryEntry(UUID.randomUUID(), namespace, key, kind, content, 1, now, now);
            entries.put(entry.memoryId(), new StoredMemory(entry, true));
            appendEvent(entry.memoryId(), "CREATE", 1, now);
            return entry;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<MemoryEntry> update(String namespace, UUID memoryId, long expectedVersion,
                                        MemoryKind kind, String content) {
        lock.writeLock().lock();
        try {
            ensureReadable();
            StoredMemory stored = entries.get(memoryId);
            if (stored == null || !stored.active || !stored.entry.namespace().equals(namespace)
                    || stored.entry.version() != expectedVersion) return Optional.empty();
            Instant now = Instant.now();
            MemoryEntry old = stored.entry;
            MemoryEntry updated = new MemoryEntry(memoryId, namespace, old.key(), kind, content,
                    expectedVersion + 1, old.createdAt(), now);
            entries.put(memoryId, new StoredMemory(updated, true));
            appendEvent(memoryId, "UPDATE", updated.version(), now);
            return Optional.of(updated);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean delete(String namespace, UUID memoryId, long expectedVersion) {
        lock.writeLock().lock();
        try {
            ensureReadable();
            StoredMemory stored = entries.get(memoryId);
            if (stored == null || !stored.active || !stored.entry.namespace().equals(namespace)
                    || stored.entry.version() != expectedVersion) return false;
            Instant now = Instant.now();
            entries.remove(memoryId);
            appendEvent(memoryId, "DELETE", expectedVersion + 1, now);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<MemoryEntry> findByKey(String namespace, String key) {
        lock.readLock().lock();
        try {
            ensureReadable();
            return activeByKey(namespace, key).map(StoredMemory::entry);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<MemoryEntry> listActive(String namespace, int limit) {
        lock.readLock().lock();
        try {
            ensureReadable();
            return entries.values().stream().filter(StoredMemory::active)
                    .map(StoredMemory::entry).filter(entry -> entry.namespace().equals(namespace))
                    .sorted(Comparator.comparing(MemoryEntry::memoryId)).limit(limit).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int countActive(String namespace) {
        lock.readLock().lock();
        try {
            ensureReadable();
            return (int) entries.values().stream().filter(StoredMemory::active)
                    .map(StoredMemory::entry).filter(entry -> entry.namespace().equals(namespace)).count();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<MemoryEvent> events(UUID memoryId) {
        lock.readLock().lock();
        try {
            ensureReadable();
            return events.stream().filter(event -> event.memoryId().equals(memoryId)).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Fault switch for deterministic read-failure tests; production code uses MemoryStore only. */
    public void failReads(boolean value) { failReads = value; }

    private Optional<StoredMemory> activeByKey(String namespace, String key) {
        return entries.values().stream().filter(StoredMemory::active)
                .filter(stored -> stored.entry.namespace().equals(namespace) && stored.entry.key().equals(key))
                .findFirst();
    }

    private void appendEvent(UUID memoryId, String operation, long version, Instant now) {
        events.add(new MemoryEvent(nextEventId++, memoryId, operation, version, now, "EXPLICIT_CLI"));
    }

    private void ensureReadable() {
        if (failReads) throw new IllegalStateException("Memory store unavailable");
    }

    private record StoredMemory(MemoryEntry entry, boolean active) { }
}
