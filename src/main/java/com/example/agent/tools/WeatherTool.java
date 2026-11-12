package com.example.agent.tools;

import com.example.agent.tool.AgentTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 固定数据的模拟天气工具。
 *
 * <p>这个工具只用于演示完整的模型工具调用链，不访问真实天气服务。
 * 结果中包含模拟数据标记，便于 Runtime 学习者区分“工具返回的数据”和实时事实。
 */
public final class WeatherTool implements AgentTool {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 城市名是唯一必填参数，具体天气由模拟数据固定返回。 */
    private static final JsonNode SCHEMA = createSchema();

    /** 构造提供给模型的城市参数 Schema。 */
    private static JsonNode createSchema() {
        ObjectNode root = JSON.createObjectNode();
        root.put("type", "object");
        root.putObject("properties").putObject("city")
                .put("type", "string").put("description", "城市名称");
        root.putArray("required").add("city");
        return root;
    }

    @Override public String name() { return "weather"; }
    @Override public String description() { return "获取模拟天气数据（非实时），用于演示工具调用。"; }
    @Override public JsonNode inputSchema() { return SCHEMA.deepCopy(); }

    /** 返回模拟天气 JSON；没有外部网络副作用。 */
    @Override public JsonNode execute(JsonNode arguments) {
        String city = arguments.path("city").asText();
        ObjectNode output = JSON.createObjectNode();
        output.put("city", city);
        output.put("condition", "晴转多云");
        output.put("temperatureCelsius", 22);
        output.put("humidityPercent", 45);
        output.put("source", "模拟数据，仅用于演示");
        return output;
    }
}
