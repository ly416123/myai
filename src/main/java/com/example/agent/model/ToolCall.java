package com.example.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * 模型发出的单个工具调用请求。
 *
 * <p>id 是模型为本次调用分配的关联标识；name 用于在 ToolRegistry 中查找实现；
 * arguments 是尚未执行的 JSON 参数。保留 JSON 形态可以先按工具 Schema 校验，
 * 再由具体工具解释参数，而不用在模型适配层绑定到某个 Java 参数类。
 */
public record ToolCall(String id, String name, JsonNode arguments) {
    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        // 没有参数时使用空对象，深拷贝避免外部改写调用内容。
        arguments = arguments == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : arguments.deepCopy();
    }
}
