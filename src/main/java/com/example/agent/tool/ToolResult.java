package com.example.agent.tool;

import com.example.agent.workflow.FailureCategory;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 单次工具执行的结构化结果。
 *
 * <p>成功时携带 JSON output；失败时携带可读 error。两种情况都会关联原 toolCallId，
 * 并通过 {@link #asMessageContent()} 转为 tool 消息内容交给模型继续处理。
 * 这样工具异常通常只会成为本次任务中的一条失败结果，不会直接打断整个 CLI 进程。
 */
public record ToolResult(String toolCallId, String toolName, boolean success, JsonNode output, String error,
                         FailureCategory failureCategory, boolean outcomeUnknown) {
    /** 创建成功结果。 */
    public static ToolResult success(String id, String name, JsonNode output) {
        return new ToolResult(id, name, true, output, null, null, false);
    }
    /** 创建失败结果；失败时不设置 output。 */
    public static ToolResult failure(String id, String name, String error) {
        return failure(id, name, error, FailureCategory.TOOL_EXECUTION);
    }
    public static ToolResult failure(String id, String name, String error, FailureCategory category) {
        return new ToolResult(id, name, false, null, error, category, false);
    }
    public static ToolResult indeterminate(String id, String name, String error) {
        return new ToolResult(id, name, false, null, error, FailureCategory.TOOL_TIMEOUT_UNKNOWN, true);
    }
    /**
     * 将结果转换为可放入 tool 消息的 JSON 文本。
     *
     * <p>成功直接序列化结构化输出；失败统一包装成 error 字段，
     * 使模型能识别错误内容，同时避免错误字符串破坏 JSON 格式。
     */
    public String asMessageContent() {
        if (success) return output == null ? "null" : output.toString();
        return "{\"error\":" + quote(error == null ? "Tool execution failed" : error) + "}";
    }
    private static String quote(String value) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { return "\"Tool execution failed\""; }
    }
}
