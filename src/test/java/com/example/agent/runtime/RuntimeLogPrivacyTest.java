package com.example.agent.runtime;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.agent.client.ModelClient;
import com.example.agent.model.ModelResponse;
import com.example.agent.model.ToolCall;
import com.example.agent.tool.ToolExecutor;
import com.example.agent.tool.ToolRegistry;
import com.example.agent.workflow.InMemoryRunStore;
import com.example.agent.workflow.RuntimeConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeLogPrivacyTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void doesNotWriteUnregisteredModelToolNameOrArgumentsToLogs() throws Exception {
        String sensitiveToolName = "private-customer-identifier-must-not-be-logged";
        String sensitiveArgument = "private-tool-argument-must-not-be-logged";
        var arguments = JSON.readTree("{\"value\":\"" + sensitiveArgument + "\"}");
        AtomicInteger requests = new AtomicInteger();
        ModelClient model = (messages, tools) -> requests.getAndIncrement() == 0
                ? new ModelResponse("", List.of(new ToolCall("call-private", sensitiveToolName,
                        arguments)))
                : new ModelResponse("Handled safely", List.of());

        Logger runtimeLogger = (Logger) LoggerFactory.getLogger(AgentRuntime.class);
        Logger toolLogger = (Logger) LoggerFactory.getLogger(ToolExecutor.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        runtimeLogger.addAppender(captured);
        toolLogger.addAppender(captured);

        ToolRegistry registry = new ToolRegistry();
        try (ToolExecutor executor = new ToolExecutor(registry, Duration.ofSeconds(1))) {
            AgentRuntime runtime = new AgentRuntime(model, registry, executor,
                    new InMemoryRunStore(), RuntimeConfig.defaults(3));

            assertEquals("Handled safely", runtime.runTracked("safe request").finalAnswer());
        } finally {
            runtimeLogger.detachAppender(captured);
            toolLogger.detachAppender(captured);
            captured.stop();
        }

        String logOutput = captured.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertFalse(logOutput.contains(sensitiveToolName), "model-controlled unknown tool names must not enter logs");
        assertFalse(logOutput.contains(sensitiveArgument), "tool arguments must not enter logs");
    }
}
