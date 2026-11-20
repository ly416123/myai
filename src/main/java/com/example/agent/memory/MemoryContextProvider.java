package com.example.agent.memory;

/** Resolves one bounded memory context when a new Run is accepted. */
@FunctionalInterface
public interface MemoryContextProvider {
    MemoryContextSnapshot snapshot(String query);

    static MemoryContextProvider empty() { return ignored -> MemoryContextSnapshot.empty(); }
}
