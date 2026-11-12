package com.example.agent.runtime;

/**
 * Agent 在最大模型请求次数内没有得到最终文本回答时抛出的受控异常。
 *
 * <p>这里的“次数”统计模型请求次数，而不是工具执行次数；一次响应中的多个工具调用
 * 仍只对应一轮模型调用。CLI 可捕获此异常并向用户显示简洁提示。
 */
public final class AgentMaxIterationsException extends RuntimeException {
    /** 创建带有轮数信息的终止原因。 */
    public AgentMaxIterationsException(String message) { super(message); }
}
