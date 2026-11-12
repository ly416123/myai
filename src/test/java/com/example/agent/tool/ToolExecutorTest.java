package com.example.agent.tool;

import com.example.agent.model.ToolCall;
import com.example.agent.tools.CalculatorTool;
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
}
