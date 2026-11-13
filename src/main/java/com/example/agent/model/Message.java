package com.example.agent.model;

import java.util.List;
import java.util.Objects;

/**
 * 厂商无关的对话消息。
 *
 * <p>消息历史是模型上下文的载体：user 表示用户输入，assistant 表示模型输出，
 * tool 表示本地工具执行结果。assistant 工具调用消息通过 toolCalls 保存调用请求；
 * 每条 tool 消息通过 toolCallId 回指对应请求。
 *
 * <p>它类似传统后端中用于外部协议转换的统一 DTO，避免对话历史直接保存 SDK 对象。
 */
public record Message(Role role, String content, List<ToolCall> toolCalls,
                      String toolCallId, String name) {
    public Message {
        Objects.requireNonNull(role, "role");
        content = content == null ? "" : content;
        // 防止调用方随后修改原 List，破坏已经写入上下文的历史消息。
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** 创建系统指令消息。 */
    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, List.of(), null, null);
    }

    /** 创建普通用户消息。 */
    public static Message user(String content) {
        return new Message(Role.USER, content, List.of(), null, null);
    }

    /** 创建普通 assistant 文本消息。 */
    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content, List.of(), null, null);
    }

    /** 创建携带一个或多个工具调用请求的 assistant 消息。 */
    public static Message assistantToolCalls(List<ToolCall> calls) {
        return new Message(Role.ASSISTANT, "", calls, null, null);
    }

    /** 创建工具执行结果消息，并用调用 ID 与模型发出的请求关联。 */
    public static Message toolResult(String toolCallId, String toolName, String content) {
        return new Message(Role.TOOL, content, List.of(), toolCallId, toolName);
    }

    /** 对话协议中的消息角色。 */
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }
}
