package com.example.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 工具参数的 JSON Schema 编译与校验边界。
 *
 * <p>Schema 在工具注册/执行器构造时编译并缓存，避免每次工具调用重复解析；
 * 默认采用 Draft 2020-12，未声明方言的 Schema 也按该版本处理。校验失败只返回布尔值，
 * 调用方可以返回统一错误，而不把 Schema 内部信息或用户参数写入日志。
 *
 * <p>为了避免由工具 Schema 解析触发任意网络访问，$ref、$dynamicRef 和 $recursiveRef
 * 只允许指向当前 Schema 文档内的片段。工具参数由模型提供，但 Schema 来自服务端注册的工具。
 */
final class ToolInputSchemaValidator {
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12);

    /** 已编译 Schema 的缓存；工具注册表以工具名保证实现唯一。 */
    private final ConcurrentMap<String, CompiledSchema> schemas = new ConcurrentHashMap<>();

    /** 注册工具时预编译 Schema，让静态定义错误尽早暴露。 */
    void register(AgentTool tool) {
        JsonNode schema = requireSchema(tool);
        schemas.put(tool.name(), compile(schema));
    }

    /** 校验一份调用参数；Schema 发生变化时会重新编译对应缓存项。 */
    boolean isValid(AgentTool tool, JsonNode arguments) {
        JsonNode declaredSchema = requireSchema(tool);
        CompiledSchema compiled = schemas.compute(tool.name(), (name, current) ->
                current != null && current.source().equals(declaredSchema) ? current : compile(declaredSchema));
        return arguments != null && compiled.validator().validate(arguments).isEmpty();
    }

    /** 工具协议要求参数 Schema 根节点为对象，并复制节点隔离调用方的后续修改。 */
    private static JsonNode requireSchema(AgentTool tool) {
        JsonNode schema = tool.inputSchema();
        if (schema == null || !schema.isObject()) {
            throw new IllegalArgumentException("Tool input schema must be a JSON object");
        }
        return schema.deepCopy();
    }

    /** 阻止远程引用后交给标准 Draft 2020-12 校验器编译。 */
    private static CompiledSchema compile(JsonNode schema) {
        rejectExternalReferences(schema);
        return new CompiledSchema(schema, REGISTRY.getSchema(schema.toString(), InputFormat.JSON));
    }

    /** 递归检查 Schema 树，只接受文档内 JSON Pointer/锚点引用。 */
    private static void rejectExternalReferences(JsonNode node) {
        if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                String keyword = field.getKey();
                if (keyword.equals("$ref") || keyword.equals("$dynamicRef") || keyword.equals("$recursiveRef")) {
                    JsonNode reference = field.getValue();
                    if (!reference.isTextual() || !reference.asText().startsWith("#")) {
                        throw new IllegalArgumentException("External tool schema references are disabled");
                    }
                }
                rejectExternalReferences(field.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(ToolInputSchemaValidator::rejectExternalReferences);
        }
    }

    /** source 用于发现工具 Schema 变更，validator 是可并发复用的编译结果。 */
    private record CompiledSchema(JsonNode source, Schema validator) { }
}
