package com.example.agent.memory;

import org.springframework.dao.DuplicateKeyException;
import com.example.agent.observability.AgentTelemetry;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Explicit memory management and bounded retrieval policy for one configured data namespace. */
public final class MemoryService implements MemoryContextProvider {
    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(?:\\b(?:api[_-]?key|password|secret|access[_-]?token)\\s*[:=]\\s*\\S+"
                    + "|\\bbearer\\s+[a-z0-9._~+/-]+=*|\\bsk-[a-z0-9-]{8,}"
                    + "|\\bgh[pousr]_[a-z0-9_]{20,}|\\bAKIA[0-9A-Z]{16}\\b)");
    private final MemoryStore store;
    private final MemoryRetriever retriever;
    private final String namespace;
    private final int namespaceLimit;
    private final AgentTelemetry telemetry;

    public MemoryService(MemoryStore store, MemoryRetriever retriever, String namespace, int namespaceLimit) {
        this(store, retriever, namespace, namespaceLimit, AgentTelemetry.noop());
    }

    public MemoryService(MemoryStore store, MemoryRetriever retriever, String namespace, int namespaceLimit,
                         AgentTelemetry telemetry) {
        if (store == null || retriever == null || namespace == null || namespace.isBlank()
                || namespace.length() > 120 || namespaceLimit < 1 || namespaceLimit > 500 || telemetry == null) {
            throw new IllegalArgumentException("Invalid memory service configuration");
        }
        this.store = store;
        this.retriever = retriever;
        this.namespace = namespace;
        this.namespaceLimit = namespaceLimit;
        this.telemetry = telemetry;
    }

    public MemoryEntry remember(String key, MemoryKind kind, String content) {
        String normalizedKey = validateKey(key);
        validateContent(kind, content);
        long started = System.nanoTime();
        try {
            MemoryEntry created = store.create(namespace, normalizedKey, kind, content, namespaceLimit);
            record("remember", "success", started);
            return created;
        } catch (DuplicateKeyException duplicate) {
            record("remember", "conflict", started);
            throw new MemoryAlreadyExistsException();
        } catch (com.example.agent.memory.MemoryLimitExceededException exceeded) {
            record("remember", "rejected", started);
            throw new MemoryService.MemoryLimitExceededException();
        } catch (MemoryStoreUnavailableException unavailable) {
            record("remember", "failure", started);
            throw new MemoryOperationException();
        }
    }

    public MemoryEntry update(String key, MemoryKind kind, String content) {
        String normalizedKey = validateKey(key);
        validateContent(kind, content);
        long started = System.nanoTime();
        MemoryEntry current = find(normalizedKey).orElseThrow(MemoryNotFoundException::new);
        try {
            Optional<MemoryEntry> updated = store.update(namespace, current.memoryId(), current.version(), kind, content);
            if (updated.isEmpty()) {
                record("update", "conflict", started);
                throw new MemoryConflictException();
            }
            record("update", "success", started);
            return updated.get();
        } catch (MemoryStoreUnavailableException unavailable) {
            record("update", "failure", started);
            throw new MemoryOperationException();
        }
    }

    public void delete(String key) {
        String normalizedKey = validateKey(key);
        long started = System.nanoTime();
        MemoryEntry current = find(normalizedKey).orElseThrow(MemoryNotFoundException::new);
        try {
            if (!store.delete(namespace, current.memoryId(), current.version())) {
                record("delete", "conflict", started);
                throw new MemoryConflictException();
            }
            record("delete", "success", started);
        } catch (MemoryStoreUnavailableException unavailable) {
            record("delete", "failure", started);
            throw new MemoryOperationException();
        }
    }

    public Optional<MemoryEntry> find(String key) {
        String normalizedKey = validateKey(key);
        long started = System.nanoTime();
        try {
            Optional<MemoryEntry> found = store.findByKey(namespace, normalizedKey);
            record("find", found.isPresent() ? "success" : "not_found", started);
            return found;
        } catch (MemoryStoreUnavailableException unavailable) {
            record("find", "failure", started);
            throw new MemoryOperationException();
        }
    }

    public List<MemoryEntry> list() {
        long started = System.nanoTime();
        try {
            List<MemoryEntry> entries = store.listActive(namespace, namespaceLimit);
            record("list", "success", started);
            return entries;
        } catch (MemoryStoreUnavailableException unavailable) {
            record("list", "failure", started);
            throw new MemoryOperationException();
        }
    }

    @Override
    public MemoryContextSnapshot snapshot(String query) {
        if (query == null) throw new IllegalArgumentException("Memory query is required");
        long started = System.nanoTime();
        try {
            MemoryContextSnapshot snapshot = retriever.retrieve(query, store.listActive(namespace, namespaceLimit));
            record("retrieve", "success", started);
            return snapshot;
        } catch (MemoryStoreUnavailableException unavailable) {
            record("retrieve", "degraded", started);
            return MemoryContextSnapshot.degradedEmpty();
        }
    }

    private void record(String operation, String result, long startedNanos) {
        telemetry.recordMemoryOperation(operation, result,
                java.time.Duration.ofNanos(Math.max(0, System.nanoTime() - startedNanos)));
    }

    private static String validateKey(String key) {
        if (key == null) throw new MemoryValidationException();
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        if (!KEY.matcher(normalized).matches()) throw new MemoryValidationException();
        return normalized;
    }

    private static void validateContent(MemoryKind kind, String content) {
        if (kind == null || content == null || content.isBlank()
                || content.codePointCount(0, content.length()) > 2_000 || CREDENTIAL.matcher(content).find()) {
            throw new MemoryValidationException();
        }
    }

    public static final class MemoryValidationException extends IllegalArgumentException {
        public MemoryValidationException() { super("Memory input is invalid or exceeds its allowed format"); }
    }
    public static final class MemoryAlreadyExistsException extends IllegalStateException {
        public MemoryAlreadyExistsException() { super("Memory key already exists; use update to change it"); }
    }
    public static final class MemoryNotFoundException extends IllegalArgumentException {
        public MemoryNotFoundException() { super("Memory entry was not found"); }
    }
    public static final class MemoryConflictException extends IllegalStateException {
        public MemoryConflictException() { super("Memory entry changed concurrently; retry the command"); }
    }
    public static final class MemoryLimitExceededException extends IllegalStateException {
        public MemoryLimitExceededException() { super("Memory namespace limit reached; delete an entry first"); }
    }
    public static final class MemoryOperationException extends IllegalStateException {
        public MemoryOperationException() { super("Memory operation could not be completed"); }
    }
}
