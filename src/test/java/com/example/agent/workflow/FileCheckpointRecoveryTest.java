package com.example.agent.workflow;

import com.example.agent.client.ModelClient;
import com.example.agent.model.Message;
import com.example.agent.model.ModelResponse;
import com.example.agent.model.ToolCall;
import com.example.agent.runtime.AgentRuntime;
import com.example.agent.tool.AgentTool;
import com.example.agent.tool.ToolExecutor;
import com.example.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 回归评测：从新建的文件存储对象读取检查点并继续执行安全的 PENDING 工具步骤。 */
class FileCheckpointRecoveryTest {
    @TempDir Path directory;

    @Test
    void resumesFromPersistedPendingToolCheckpoint() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JsonFileRunStore firstProcessStore = new JsonFileRunStore(directory, mapper);
        ToolCall call = new ToolCall("file-call", "counter", mapper.createObjectNode());
        RunRecord interrupted = RunRecord.create("persistent-run", "test recovery", Duration.ofMinutes(2));
        interrupted.transitionTo(RunStatus.RUNNING, Instant.now(), null);
        interrupted.addMessage(Message.user("test recovery"));
        interrupted.addMessage(Message.assistantToolCalls(List.of(call)));
        interrupted.addStep(RunStep.toolPending("file-step", call.id(), call.name(), Instant.now()));
        firstProcessStore.save(interrupted);

        AtomicInteger executions = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CounterTool(executions, mapper));
        ModelClient finalAnswer = (messages, definitions) -> new ModelResponse("recovered", List.of());
        try (ToolExecutor executor = new ToolExecutor(registry, Duration.ofSeconds(1))) {
            // 使用新 store 实例，验证从磁盘反序列化，而不是继续使用原进程中的对象。
            JsonFileRunStore restartedProcessStore = new JsonFileRunStore(directory, mapper);
            AgentRuntime runtime = new AgentRuntime(finalAnswer, registry, executor, restartedProcessStore,
                    new RuntimeConfig(5, 3, 0, Duration.ofSeconds(10)));
            RunRecord result = runtime.resume("persistent-run");

            assertEquals(RunStatus.SUCCEEDED, result.status());
            assertEquals(1, executions.get());
            assertTrue(result.resumed());
            assertEquals("recovered", result.finalAnswer());
        }
    }

    private record CounterTool(AtomicInteger executions, ObjectMapper mapper) implements AgentTool {
        @Override public String name() { return "counter"; }
        @Override public String description() { return "increments a deterministic test counter"; }
        @Override public JsonNode inputSchema() {
            return mapper.createObjectNode().put("type", "object").set("properties", mapper.createObjectNode());
        }
        @Override public JsonNode execute(JsonNode arguments) {
            return mapper.createObjectNode().put("execution", executions.incrementAndGet());
        }
    }
}
