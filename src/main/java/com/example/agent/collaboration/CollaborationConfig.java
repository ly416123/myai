package com.example.agent.collaboration;

import java.time.Duration;

/** 一个父 Run 的并发、任务数和全局调用预算。 */
public record CollaborationConfig(int maxTasks, int maxParallelism, int maxModelCalls,
                                  int maxToolCalls, int maxModelRetries,
                                  Duration timeout, Duration toolTimeout, boolean separateTaskWorkers) {
    public CollaborationConfig(int maxTasks, int maxParallelism, int maxModelCalls,
                               int maxToolCalls, int maxModelRetries,
                               Duration timeout, Duration toolTimeout) {
        this(maxTasks, maxParallelism, maxModelCalls, maxToolCalls, maxModelRetries,
                timeout, toolTimeout, false);
    }
    public CollaborationConfig {
        if (maxTasks < 1 || maxParallelism < 1 || maxModelCalls < 1 || maxToolCalls < 0 || maxModelRetries < 0)
            throw new IllegalArgumentException("Invalid collaboration budgets");
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || toolTimeout == null || toolTimeout.isNegative() || toolTimeout.isZero())
            throw new IllegalArgumentException("Timeouts must be positive");
    }

    public static CollaborationConfig defaults() {
        return new CollaborationConfig(8, 2, 20, 20, 1, Duration.ofMinutes(5), Duration.ofSeconds(10));
    }
}
