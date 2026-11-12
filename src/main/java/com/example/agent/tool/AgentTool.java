package com.example.agent.tool;

import com.example.agent.model.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 可注册到 Agent 中的本地工具契约。
 *
 * <p>工具由模型提出调用请求，但真正执行的是这里的 Java 方法。接口同时暴露给模型的
 * 描述和参数 Schema，因此“模型能看懂什么”与“Runtime 能调用什么”来自同一实现。
 *
 * <p>传统后端中它类似一个有明确输入输出约定的业务 Service；若使用 Spring AI，
 * 该能力通常会以带工具注解的方法或 ToolCallback 暴露给模型。
 */
public interface AgentTool {
    /** 返回模型调用时使用的唯一工具名，也用于注册表查找。 */
    String name();

    /** 描述工具适用场景，帮助模型在多个工具之间选择。 */
    String description();

    /** 返回符合 JSON Schema 规范的参数对象定义。 */
    JsonNode inputSchema();

    /**
     * 执行工具逻辑。
     *
     * @param arguments 经 ToolExecutor 按 inputSchema 校验过的 JSON 参数
     * @return 可序列化为 JSON 并回传给模型的结构化结果
     * @throws Exception 工具执行失败时抛出；ToolExecutor 会捕获并转换为失败结果
     */
    JsonNode execute(JsonNode arguments) throws Exception;

    /** 把本地工具元数据转换成传给模型的统一定义，不包含 execute 实现。 */
    default ToolDefinition definition() {
        return new ToolDefinition(name(), description(), inputSchema());
    }
}
