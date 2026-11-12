package com.example.agent.runtime;

import com.example.agent.client.ModelClient;
import com.example.agent.model.Message;
import com.example.agent.model.ModelResponse;
import com.example.agent.model.ToolCall;
import com.example.agent.model.ToolDefinition;
import com.example.agent.tool.ToolExecutor;
import com.example.agent.tool.ToolRegistry;
import com.example.agent.tools.CalculatorTool;
import com.example.agent.tools.WeatherTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentRuntime 的无网络行为测试。
 *
 * <p>通过替身 ModelClient 预设响应，测试控制循环是否按协议保留 assistant 工具调用消息、
 * 收齐多个工具结果后再请求模型，以及工具循环是否受最大模型调用次数限制。
 */
class AgentRuntimeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    /** 一次模型响应中的多个工具调用都要执行，且结果 ID 要与调用 ID 一一对应。 */
    void executesMultipleToolCallsFromOneModelResponseBeforeCallingModelAgain() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CalculatorTool());
        registry.register(new WeatherTool());
        List<List<Message>> observedHistory = new ArrayList<>();
        AtomicInteger callCount = new AtomicInteger();
        ModelResponse toolsResponse = new ModelResponse("", List.of(
                new ToolCall("call-1", "calculator", JSON.readTree("{\"operation\":\"add\",\"a\":2,\"b\":3}")),
                new ToolCall("call-2", "weather", JSON.readTree("{\"city\":\"北京\"}"))));
        ModelClient model = (messages, tools) -> {
            observedHistory.add(List.copyOf(messages));
            if (callCount.getAndIncrement() == 0) return toolsResponse;
            return new ModelResponse("计算结果为 5，北京天气为模拟晴转多云。", List.of());
        };
        try (ToolExecutor executor = new ToolExecutor(registry, Duration.ofSeconds(2))) {
            AgentRuntime runtime = new AgentRuntime(model, registry, executor, 10);
            assertEquals("计算结果为 5，北京天气为模拟晴转多云。", runtime.run("算一下 2+3，再查北京天气"));
        }
        assertEquals(2, callCount.get());
        List<Message> secondRequest = observedHistory.get(1);
        assertEquals(Message.Role.ASSISTANT, secondRequest.get(1).role());
        assertEquals(2, secondRequest.get(1).toolCalls().size());
        assertEquals("call-1", secondRequest.get(2).toolCallId());
        assertEquals("call-2", secondRequest.get(3).toolCallId());
    }

    @Test
    /** 每一轮都继续请求工具时，Runtime 必须在配置的上限处停止。 */
    void stopsAfterConfiguredNumberOfModelCalls() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CalculatorTool());
        ModelResponse repeatedToolCall = new ModelResponse("", List.of(
                new ToolCall("call-1", "calculator", JSON.readTree("{\"operation\":\"add\",\"a\":1,\"b\":1}"))));
        ModelClient alwaysCallsTool = (messages, tools) -> repeatedToolCall;
        try (ToolExecutor executor = new ToolExecutor(registry, Duration.ofSeconds(2))) {
            AgentRuntime runtime = new AgentRuntime(alwaysCallsTool, registry, executor, 2);
            assertThrows(AgentMaxIterationsException.class, () -> runtime.run("continue"));
        }
    }
}
