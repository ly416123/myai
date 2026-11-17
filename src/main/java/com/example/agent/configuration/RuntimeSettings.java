package com.example.agent.configuration;

/** Run and collaboration budgets captured as part of an Agent configuration revision. */
public record RuntimeSettings(int maxModelCalls, int maxToolCalls, int maxModelRetries,
                              long taskTimeoutMillis, long toolTimeoutMillis, int maxTasks, int maxParallelism) {
    public RuntimeSettings(int maxModelCalls, int maxToolCalls, int maxModelRetries,
                           long taskTimeoutMillis, int maxTasks, int maxParallelism) {
        this(maxModelCalls, maxToolCalls, maxModelRetries, taskTimeoutMillis, 10_000, maxTasks, maxParallelism);
    }

    public RuntimeSettings {
        if (maxModelCalls < 1 || maxToolCalls < 0 || maxModelRetries < 0 || taskTimeoutMillis < 1 || toolTimeoutMillis < 1
                || maxTasks < 1 || maxParallelism < 1 || maxParallelism > maxTasks) {
            throw new IllegalArgumentException("Runtime settings are invalid");
        }
    }
}
