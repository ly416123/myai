package com.example.agent.collaboration;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** 静态 Worker 定义：职责、指令、工具 allowlist 和单任务预算上限。 */
public record WorkerSpec(String workerId, String responsibility, String systemPrompt,
                         List<String> allowedTools, int maxModelCalls, int maxToolCalls,
                         String version, String planningSummary, JsonNode inputSchema,
                         String inputSummary, String outputSummary, JsonNode outputSchema) {
    public WorkerSpec {
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        if (workerId == null || workerId.isBlank()) throw new IllegalArgumentException("Worker ID is required");
        if (responsibility == null || responsibility.isBlank()) throw new IllegalArgumentException("Worker responsibility is required");
        if (systemPrompt == null || systemPrompt.isBlank()) throw new IllegalArgumentException("Worker prompt is required");
        if (maxModelCalls < 1 || maxToolCalls < 0) throw new IllegalArgumentException("Invalid worker budget");
        if (version != null && version.isBlank()) throw new IllegalArgumentException("Worker version must not be blank");
        inputSchema = copy(inputSchema);
        outputSchema = copy(outputSchema);
        if (version != null) {
            requireText(planningSummary, "Worker planning summary");
            requireText(inputSummary, "Worker input summary");
            requireText(outputSummary, "Worker output summary");
            if (inputSchema == null || !inputSchema.isObject()) {
                throw new IllegalArgumentException("Worker input schema must be a JSON object");
            }
            if (outputSchema == null || !outputSchema.isObject()) {
                throw new IllegalArgumentException("Worker output schema must be a JSON object");
            }
            if (!"object".equals(inputSchema.path("type").asText())
                    || !"object".equals(outputSchema.path("type").asText())) {
                throw new IllegalArgumentException("Worker input and output schemas must declare object roots");
            }
        }
    }

    /** 迁移期兼容构造器；阶段五静态配置应使用完整的版本化契约构造器。 */
    public WorkerSpec(String workerId, String responsibility, String systemPrompt,
                      List<String> allowedTools, int maxModelCalls, int maxToolCalls) {
        this(workerId, responsibility, systemPrompt, allowedTools, maxModelCalls, maxToolCalls,
                null, responsibility, null, "", responsibility, null);
    }

    @Override public JsonNode inputSchema() { return copy(inputSchema); }
    @Override public JsonNode outputSchema() { return copy(outputSchema); }

    private static JsonNode copy(JsonNode value) { return value == null ? null : value.deepCopy(); }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
