package com.example.agent.workflow;

import java.time.Duration;
import java.util.Objects;

/** Agent Workflow 的单次运行预算。 */
public record RuntimeConfig(int maxModelCalls, int maxToolCalls, int maxModelRetries, Duration taskTimeout) {
    public RuntimeConfig {
        if (maxModelCalls < 1) throw new IllegalArgumentException("maxModelCalls must be at least 1");
        if (maxToolCalls < 0) throw new IllegalArgumentException("maxToolCalls cannot be negative");
        if (maxModelRetries < 0) throw new IllegalArgumentException("maxModelRetries cannot be negative");
        Objects.requireNonNull(taskTimeout, "taskTimeout");
        if (taskTimeout.isZero() || taskTimeout.isNegative()) throw new IllegalArgumentException("taskTimeout must be positive");
    }

    /** 基础循环的兼容默认值，加上有限重试、工具调用预算和任务时限。 */
    public static RuntimeConfig defaults(int maxModelCalls) {
        return new RuntimeConfig(maxModelCalls, 20, 2, Duration.ofMinutes(5));
    }
}
