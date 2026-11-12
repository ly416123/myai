package com.example.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * 传给模型的工具说明和 JSON Schema。
 *
 * <p>这是工具的模型侧“目录条目”：description 帮助模型判断何时调用，inputSchema
 * 告诉模型应生成哪些参数。它不包含 Java 执行逻辑；实际实现由 AgentTool 提供。
 */
public record ToolDefinition(String name, String description, JsonNode inputSchema) {
    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(inputSchema, "inputSchema");
        // Schema 作为定义的一部分应保持稳定，避免调用方在注册后修改内容。
        inputSchema = inputSchema.deepCopy();
    }
}
