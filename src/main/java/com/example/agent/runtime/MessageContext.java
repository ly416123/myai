package com.example.agent.runtime;

import com.example.agent.model.Message;
import java.util.ArrayList;
import java.util.List;

/**
 * 单次 Agent 任务的内存消息历史。
 *
 * <p>每次 AgentRuntime.run 都创建新的 MessageContext，因此当前实现不跨用户输入保存会话。
 * 通过只读快照暴露消息，避免模型客户端意外修改 Runtime 正在维护的历史列表。
 * 传统后端里可把它理解为一个请求范围的 DTO/工作流上下文；持久化会话可以在后续版本接入。
 */
public final class MessageContext {
    /** 按发生顺序保存的对话消息。 */
    private final List<Message> messages = new ArrayList<>();

    /** 追加一条消息；追加顺序就是下一次模型请求看到的顺序。 */
    public void add(Message message) { messages.add(message); }

    /** 按给定顺序追加多条消息。 */
    public void addAll(List<Message> additions) { messages.addAll(additions); }

    /** 返回当前历史的不可变快照。 */
    public List<Message> messages() { return List.copyOf(messages); }
}
