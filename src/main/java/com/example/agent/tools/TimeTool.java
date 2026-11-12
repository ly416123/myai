package com.example.agent.tools;

import com.example.agent.tool.AgentTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.DateTimeException;

/**
 * 查询指定时区当前时间的演示工具。
 *
 * <p>时间由 Java 标准库 java.time 读取，不经过模型推测，也不依赖第三方时间 API。
 * 参数采用 IANA 时区 ID，例如 Asia/Shanghai；无效时区会变成受控工具错误。
 */
public final class TimeTool implements AgentTool {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** timezone 是必填字符串，具体合法性由 ZoneId 在执行时校验。 */
    private static final JsonNode SCHEMA = createSchema();

    /** 构造提供给模型的时区参数 Schema。 */
    private static JsonNode createSchema() {
        ObjectNode root = JSON.createObjectNode();
        root.put("type", "object");
        root.putObject("properties").putObject("timezone")
                .put("type", "string").put("description", "IANA 时区，例如 Asia/Shanghai");
        root.putArray("required").add("timezone");
        return root;
    }

    /** 模型请求中用于指向该工具的名称。 */
    @Override public String name() { return "current_time"; }
    @Override public String description() { return "查询指定 IANA 时区的当前日期和时间，例如 Asia/Shanghai、Europe/Paris。"; }
    @Override public JsonNode inputSchema() { return SCHEMA.deepCopy(); }

    /** 用目标 ZoneId 生成当前时间，并返回时区和 ISO-8601 时间字符串。 */
    @Override public JsonNode execute(JsonNode arguments) {
        String timezone = arguments.path("timezone").asText();
        try {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of(timezone));
            ObjectNode output = JSON.createObjectNode();
            output.put("timezone", timezone);
            output.put("dateTime", now.format(DateTimeFormatter.ISO_ZONED_DATE_TIME));
            return output;
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("无效的时区: " + timezone, e);
        }
    }
}
