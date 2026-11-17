package com.example.agent.memory;

import org.springframework.dao.DuplicateKeyException;

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

    public MemoryService(MemoryStore store, MemoryRetriever retriever, String namespace, int namespaceLimit) {
        if (store == null || retriever == null || namespace == null || namespace.isBlank()
                || namespace.length() > 120 || namespaceLimit < 1 || namespaceLimit > 500) {
            throw new IllegalArgumentException("Invalid memory service configuration");
        }
        this.store = store;
        this.retriever = retriever;
        this.namespace = namespace;
        this.namespaceLimit = namespaceLimit;
    }

    public MemoryEntry remember(String key, MemoryKind kind, String content) {
        String normalizedKey = validateKey(key);
        validateContent(kind, content);
        try {
            return store.create(namespace, normalizedKey, kind, content, namespaceLimit);
        } catch (DuplicateKeyException duplicate) {
            throw new MemoryAlreadyExistsException();
        } catch (com.example.agent.memory.MemoryLimitExceededException exceeded) {
            throw new MemoryService.MemoryLimitExceededException();
        } catch (MemoryStoreUnavailableException unavailable) {
            throw new MemoryOperationException();
        }
    }

    public MemoryEntry update(String key, MemoryKind kind, String content) {
        String normalizedKey = validateKey(key);
        validateContent(kind, content);
        MemoryEntry current = find(normalizedKey).orElseThrow(MemoryNotFoundException::new);
        try {
            return store.update(namespace, current.memoryId(), current.version(), kind, content)
                    .orElseThrow(MemoryConflictException::new);
        } catch (MemoryStoreUnavailableException unavailable) {
            throw new MemoryOperationException();
        }
    }

    public void delete(String key) {
        String normalizedKey = validateKey(key);
        MemoryEntry current = find(normalizedKey).orElseThrow(MemoryNotFoundException::new);
        try {
            if (!store.delete(namespace, current.memoryId(), current.version())) throw new MemoryConflictException();
        } catch (MemoryStoreUnavailableException unavailable) {
            throw new MemoryOperationException();
        }
    }

    public Optional<MemoryEntry> find(String key) {
        String normalizedKey = validateKey(key);
        try {
            return store.findByKey(namespace, normalizedKey);
        } catch (MemoryStoreUnavailableException unavailable) {
            throw new MemoryOperationException();
        }
    }

    public List<MemoryEntry> list() {
        try {
            return store.listActive(namespace, namespaceLimit);
        } catch (MemoryStoreUnavailableException unavailable) {
            throw new MemoryOperationException();
        }
    }

    @Override
    public MemoryContextSnapshot snapshot(String query) {
        if (query == null) throw new IllegalArgumentException("Memory query is required");
        try {
            return retriever.retrieve(query, store.listActive(namespace, namespaceLimit));
        } catch (MemoryStoreUnavailableException unavailable) {
            return MemoryContextSnapshot.degradedEmpty();
        }
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
