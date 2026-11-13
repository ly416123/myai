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
import com.example.agent.workflow.InMemoryRunStore;
import com.example.agent.workflow.LeaseToken;
import com.example.agent.workflow.RunRecord;
import com.example.agent.workflow.RunStore;
import com.example.agent.workflow.RunStoreUnavailableException;
import com.example.agent.workflow.RuntimeConfig;
import com.example.agent.workflow.RunLeasePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/**
 * AgentRuntime 的无网络行为测试。
 *
 * <p>通过替身 ModelClient 预设响应，测试控制循环是否按协议保留 assistant 工具调用消息、
 * 收齐多个工具结果后再请求模型，以及工具循环是否受最大模型调用次数限制。
 */
class AgentRuntimeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void transientStoreReadFailureWhilePollingModelDoesNotAbortRun() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        InMemoryRunStore store = spy(new InMemoryRunStore());
        doThrow(new RunStoreUnavailableException(new IllegalStateException()))
                .doCallRealMethod().when(store).find(anyString());
        ModelClient model = (messages, tools) -> new ModelResponse("数据库恢复后继续完成", List.of());
        RuntimeConfig config = new RuntimeConfig(2, 0, 0, Duration.ofSeconds(3));

        try (ToolExecutor executor = new ToolExecutor(registry, Duration.ofSeconds(1))) {
            AgentRuntime runtime = new AgentRuntime(model, registry, executor, store, config,
                    com.example.agent.observability.AgentTelemetry.noop(),
                    new RunLeasePolicy(Duration.ofSeconds(1), Duration.ofMillis(100)));
            RunRecord result = runtime.runTracked("短暂数据库中断测试");

            assertEquals(com.example.agent.workflow.RunStatus.SUCCEEDED, result.status());
            assertEquals("数据库恢复后继续完成", result.finalAnswer());
            assertEquals(1, result.modelCalls());
        }
    }

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

    @Test
    void shutdownRequestsCancellationAndWaitsForRunCheckpointSettlement() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        CountDownLatch modelStarted = new CountDownLatch(1);
        ModelClient blockingModel = (messages, tools) -> {
            modelStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("model wait interrupted", interrupted);
            }
            return new ModelResponse("unreachable", List.of());
        };
        try (ToolExecutor executor = new ToolExecutor(registry, Duration.ofSeconds(2));
             var caller = Executors.newSingleThreadExecutor()) {
            AgentRuntime runtime = new AgentRuntime(blockingModel, registry, executor, 10);
            var runFuture = caller.submit(() -> runtime.runTracked("shutdown me"));
            assertTrue(modelStarted.await(2, TimeUnit.SECONDS));

            runtime.close();

            assertEquals(com.example.agent.workflow.RunStatus.CANCELLED,
                    runFuture.get(2, TimeUnit.SECONDS).status());
        }
    }

    @Test
    void renewsShortRunLeaseDuringLongModelCallSoAnotherWorkerCannotTakeOver() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        RunStore store = new InMemoryRunStore();
        AtomicInteger takeovers = new AtomicInteger();
        CountDownLatch modelStarted = new CountDownLatch(1);
        ModelClient slowModel = (messages, tools) -> {
            modelStarted.countDown();
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("model interrupted", interrupted);
            }
            String runId = store.list().stream().filter(run -> run.status()
                            == com.example.agent.workflow.RunStatus.RUNNING)
                    .map(com.example.agent.workflow.RunRecord::runId).findFirst().orElseThrow();
            if (store.claim(runId, "competitor", java.time.Instant.now(), Duration.ofSeconds(1)).isPresent()) {
                takeovers.incrementAndGet();
            }
            return new ModelResponse("完成", List.of());
        };
        RuntimeConfig config = new RuntimeConfig(2, 0, 0, Duration.ofSeconds(3));
        RunLeasePolicy leasePolicy = new RunLeasePolicy(Duration.ofMillis(180), Duration.ofMillis(30));
        try (ToolExecutor executor = new ToolExecutor(registry, Duration.ofSeconds(1))) {
            AgentRuntime runtime = new AgentRuntime(slowModel, registry, executor, store, config,
                    com.example.agent.observability.AgentTelemetry.noop(), leasePolicy);
            var caller = Executors.newSingleThreadExecutor();
            try {
                var result = caller.submit(() -> runtime.runTracked("长期模型请求"));
                assertTrue(modelStarted.await(1, TimeUnit.SECONDS));
                assertEquals(com.example.agent.workflow.RunStatus.SUCCEEDED,
                        result.get(2, TimeUnit.SECONDS).status());
            } finally {
                caller.shutdownNow();
                runtime.close();
            }
        }
        assertEquals(0, takeovers.get(), "a healthy long-running model call must retain its lease");
    }

    @Test
    void staleRuntimeStopsAfterAnotherWorkerFencesItsLease() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        InMemoryRunStore store = new InMemoryRunStore();
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch modelInterrupted = new CountDownLatch(1);
        ModelClient blockingModel = (messages, tools) -> {
            modelStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
                modelInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return new ModelResponse("stale answer", List.of());
        };
        RunLeasePolicy policy = new RunLeasePolicy(Duration.ofSeconds(2), Duration.ofMillis(100));
        try (ToolExecutor executor = new ToolExecutor(registry, Duration.ofSeconds(1));
             var caller = Executors.newSingleThreadExecutor()) {
            AgentRuntime runtime = new AgentRuntime(blockingModel, registry, executor, store,
                    new RuntimeConfig(2, 0, 0, Duration.ofSeconds(5)),
                    com.example.agent.observability.AgentTelemetry.noop(), policy);
            var result = caller.submit(() -> runtime.runTracked("must not overwrite takeover"));
            assertTrue(modelStarted.await(1, TimeUnit.SECONDS));
            RunRecord inFlight = store.list().stream().filter(run -> run.status()
                    == com.example.agent.workflow.RunStatus.RUNNING).findFirst().orElseThrow();
            LeaseToken oldOwner = new LeaseToken(inFlight.runId(), inFlight.leaseOwner(), inFlight.fencingToken());
            LeaseToken newOwner = store.claim(inFlight.runId(), "replacement-worker",
                    java.time.Instant.now().plusSeconds(3), Duration.ofSeconds(2)).orElseThrow();

            RunRecord returned = result.get(2, TimeUnit.SECONDS);
            assertTrue(modelInterrupted.await(1, TimeUnit.SECONDS));
            assertEquals(com.example.agent.workflow.RunStatus.RUNNING, returned.status());
            assertNull(returned.finalAnswer());
            assertEquals("replacement-worker", store.find(inFlight.runId()).orElseThrow().leaseOwner());
            assertTrue(newOwner.fencingToken() > oldOwner.fencingToken());
            assertFalse(store.saveFenced(returned, oldOwner, java.time.Instant.now()));
            runtime.close();
        }
    }
}
