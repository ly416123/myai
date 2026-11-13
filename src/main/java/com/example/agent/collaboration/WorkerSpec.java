package com.example.agent.collaboration;

import java.util.List;

/** 静态 Worker 定义：职责、指令、工具 allowlist 和单任务预算上限。 */
public record WorkerSpec(String workerId, String responsibility, String systemPrompt,
                         List<String> allowedTools, int maxModelCalls, int maxToolCalls) {
    public WorkerSpec {
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        if (workerId == null || workerId.isBlank()) throw new IllegalArgumentException("Worker ID is required");
        if (responsibility == null || responsibility.isBlank()) throw new IllegalArgumentException("Worker responsibility is required");
        if (systemPrompt == null || systemPrompt.isBlank()) throw new IllegalArgumentException("Worker prompt is required");
        if (maxModelCalls < 1 || maxToolCalls < 0) throw new IllegalArgumentException("Invalid worker budget");
    }
}
