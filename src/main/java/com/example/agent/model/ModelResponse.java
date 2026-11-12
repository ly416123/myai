package com.example.agent.model;

import java.util.List;

/**
 * 模型响应的统一表示。
 *
 * <p>模型可能返回最终文本、一个或多个工具调用，也可能同时带有文本和工具调用。
 * Runtime 通过 {@link #hasToolCalls()} 决定是否进入工具执行分支；如果没有工具调用，
 * content 就作为本轮最终回答返回。
 */
public record ModelResponse(String content, List<ToolCall> toolCalls,
                            Long promptTokens, Long completionTokens) {
    /** 兼容只需要文本和工具调用的 fake model 与调用方；未提供的 Token 数保持未知。 */
    public ModelResponse(String content, List<ToolCall> toolCalls) {
        this(content, toolCalls, null, null);
    }

    public ModelResponse {
        content = content == null ? "" : content;
        // 响应对象不可被后续代码通过原 List 修改。
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** 当前响应是否包含工具调用请求。 */
    public boolean hasToolCalls() { return !toolCalls.isEmpty(); }
}
