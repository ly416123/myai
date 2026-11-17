package com.example.agent.memory;

/** Sanitized storage failure; deliberately does not retain a driver exception or SQL parameters. */
public final class MemoryStoreUnavailableException extends RuntimeException {
    public MemoryStoreUnavailableException() { super("Memory store is unavailable"); }
}
