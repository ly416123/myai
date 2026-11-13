package com.example.agent.tool;

import com.example.agent.model.ToolCall;
import com.example.agent.tools.CalculatorTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolExecutor 与 ToolRegistry 的基础行为测试，不访问模型服务。
 */
class ToolExecutorTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    /** 验证工具结果是结构化 JSON，并保留模型提供的调用 ID。 */
    void executesCalculatorAndReturnsStructuredJson() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CalculatorTool());
        try (ToolExecutor executor = new ToolExecutor(registry, Duration.ofSeconds(2))) {
            ToolResult result = executor.execute(new ToolCall("c1", "calculator", JSON.readTree("{\"operation\":\"multiply\",\"a\":6,\"b\":7}")));
            assertTrue(result.success());
            assertEquals(42, result.output().path("result").asInt());
            assertEquals("c1", result.toolCallId());
        }
    }

    @Test
    /** 缺少必填参数时应返回失败结果，而不是让异常逃出执行器。 */
    void validationErrorsAreReturnedAsToolFailures() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CalculatorTool());
        try (ToolExecutor executor = new ToolExecutor(registry, Duration.ofSeconds(2))) {
            ToolResult result = executor.execute(new ToolCall("c2", "calculator", JSON.readTree("{\"operation\":\"add\",\"a\":1}")));
            assertFalse(result.success());
            assertEquals(com.example.agent.workflow.FailureCategory.TOOL_ARGUMENTS, result.failureCategory());
            assertEquals("{\"error\":\"Tool arguments failed validation\"}", result.asMessageContent());
        }
    }

    @Test
    /** 工具名必须唯一，防止注册时覆盖已存在的执行实现。 */
    void duplicateToolRegistrationIsRejected() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CalculatorTool());
        assertThrows(IllegalArgumentException.class, () -> registry.register(new CalculatorTool()));
    }

    @Test
    void validatesNestedJsonSchemaConstraintsInsteadOfOnlyRootProperties() throws Exception {
        AgentTool nestedTool = schemaTool("nested", """
                {"type":"object","properties":{"settings":{"type":"object","properties":{
                  "threshold":{"type":"integer","minimum":2}},"required":["threshold"]}},
                  "required":["settings"]}
                """);
        ToolRegistry registry = new ToolRegistry();
        registry.register(nestedTool);

        try (ToolExecutor executor = new ToolExecutor(registry, Duration.ofSeconds(2))) {
            ToolResult result = executor.execute(new ToolCall("nested-1", "nested",
                    JSON.readTree("{\"settings\":{\"threshold\":1}}")));
            assertFalse(result.success());
            assertEquals(com.example.agent.workflow.FailureCategory.TOOL_ARGUMENTS, result.failureCategory());
        }
    }

    @Test
    void validatesJsonSchemaCompositionKeywords() throws Exception {
        AgentTool choiceTool = schemaTool("choice", """
                {"type":"object","properties":{"mode":{"oneOf":[
                  {"const":"fast"},{"const":"safe"}]}},"required":["mode"]}
                """);
        ToolRegistry registry = new ToolRegistry();
        registry.register(choiceTool);

        try (ToolExecutor executor = new ToolExecutor(registry, Duration.ofSeconds(2))) {
            ToolResult result = executor.execute(new ToolCall("choice-1", "choice",
                    JSON.readTree("{\"mode\":\"unsafe\"}")));
            assertFalse(result.success());
            assertEquals(com.example.agent.workflow.FailureCategory.TOOL_ARGUMENTS, result.failureCategory());
        }
    }

    @Test
    void rejectsToolSchemasThatReferenceRemoteDocuments() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(schemaTool("remote", """
                {"$ref":"https://schemas.example.invalid/tool-input.json"}
                """));

        assertThrows(IllegalArgumentException.class, () -> new ToolExecutor(registry, Duration.ofSeconds(2)));
    }

    @Test
    void validatesLocalJsonSchemaReferences() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(schemaTool("local-ref", """
                {"$defs":{"threshold":{"type":"integer","minimum":2}},
                 "type":"object","properties":{"level":{"$ref":"#/$defs/threshold"}},
                 "required":["level"]}
                """));

        try (ToolExecutor executor = new ToolExecutor(registry, Duration.ofSeconds(2))) {
            ToolResult result = executor.execute(new ToolCall("local-ref-1", "local-ref",
                    JSON.readTree("{\"level\":1}")));
            assertFalse(result.success());
            assertEquals(com.example.agent.workflow.FailureCategory.TOOL_ARGUMENTS, result.failureCategory());
        }
    }

    private static AgentTool schemaTool(String name, String schemaJson) throws Exception {
        JsonNode schema = JSON.readTree(schemaJson);
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "test schema"; }
            @Override public JsonNode inputSchema() { return schema; }
            @Override public JsonNode execute(JsonNode arguments) { return arguments.deepCopy(); }
        };
    }
}
