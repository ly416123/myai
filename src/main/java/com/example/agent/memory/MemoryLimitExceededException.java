package com.example.agent.memory;

/** Raised when adding an entry would exceed the active memory limit for a namespace. */
public final class MemoryLimitExceededException extends RuntimeException {
    public MemoryLimitExceededException() { super("Memory namespace limit exceeded"); }
}
