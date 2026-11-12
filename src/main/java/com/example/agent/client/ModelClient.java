package com.example.agent.client;

import com.example.agent.model.Message;
import com.example.agent.model.ModelResponse;
import com.example.agent.model.ToolDefinition;
import java.util.List;

/**
 * 模型客户端的厂商无关接口，是 Agent Runtime 与具体模型 SDK 之间的隔离边界。
 *
 * <p>输入是 Runtime 自己维护的消息列表和工具定义；输出是统一的模型响应。
 * 这样 AgentRuntime 不需要知道 DeepSeek SDK 的请求对象、响应对象或异常类型，
 * 将来更换模型服务时只需替换接口实现。
 *
 * <p>传统 Java 后端中，这类接口类似支付、短信等外部服务的 Gateway/Adapter。
 * 使用 Spring AI 时，ChatClient 等统一调用抽象通常承担类似职责。
 */
public interface ModelClient extends AutoCloseable {
    /**
     * 请求模型基于完整对话历史生成下一步响应。
     *
     * @param messages 本次任务的历史消息，包含 user、assistant 和 tool 消息
     * @param tools 当前可供模型选择的工具定义；为空时模型只能给出文本回答
     * @return 普通文本回答、工具调用请求，或二者兼有的统一响应
     */
    ModelResponse complete(List<Message> messages, List<ToolDefinition> tools);

    /**
     * 释放底层客户端资源。测试用的简单 ModelClient 无资源需要释放，因此提供空默认实现。
     */
    @Override
    default void close() { }
}
