package com.example.agent.tools;

import com.example.agent.tool.AgentTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 本地四则运算演示工具。
 *
 * <p>计算发生在 Java 进程内，不请求模型或外部服务。operation 使用固定枚举，
 * a/b 使用 number；ToolExecutor 会在进入 execute 前做结构校验，除数为零等业务错误
 * 则在这里检查并抛出，由执行器转换成失败结果。
 */
public final class CalculatorTool implements AgentTool {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 暴露给模型的参数约束；required 与 enum 同时作为生成提示和运行时校验依据。 */
    private static final JsonNode SCHEMA = createSchema();

    /** 显式构造 JSON Schema，避免在工具实现之外维护一份容易漂移的定义。 */
    private static JsonNode createSchema() {
        ObjectNode root = JSON.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        var operation = properties.putObject("operation");
        operation.put("type", "string");
        operation.putArray("enum").add("add").add("subtract").add("multiply").add("divide");
        properties.putObject("a").put("type", "number");
        properties.putObject("b").put("type", "number");
        root.putArray("required").add("operation").add("a").add("b");
        return root;
    }

    /** 工具名会出现在模型的函数调用请求中。 */
    @Override public String name() { return "calculator"; }
    /** 用自然语言提示模型这个工具能做什么以及需要什么信息。 */
    @Override public String description() { return "在本地执行两个数的加、减、乘、除。除法会检查除数不能为零。"; }
    /** 返回 Schema 的副本，避免调用方改写静态定义。 */
    @Override public JsonNode inputSchema() { return SCHEMA.deepCopy(); }

    /** 根据已校验的操作和两个数字在本地计算，并返回可回传给模型的 JSON 对象。 */
    @Override public JsonNode execute(JsonNode arguments) {
        String operation = arguments.path("operation").asText();
        double a = arguments.path("a").asDouble();
        double b = arguments.path("b").asDouble();
        // 用 switch 显式列出允许的操作；不执行模型传入的任意表达式或代码。
        double result = switch (operation) {
            case "add" -> a + b;
            case "subtract" -> a - b;
            case "multiply" -> a * b;
            case "divide" -> {
                if (b == 0.0) throw new IllegalArgumentException("除数不能为零");
                yield a / b;
            }
            default -> throw new IllegalArgumentException("不支持的运算: " + operation);
        };
        ObjectNode output = JSON.createObjectNode();
        output.put("operation", operation);
        output.put("a", a);
        output.put("b", b);
        output.put("result", result);
        return output;
    }
}
