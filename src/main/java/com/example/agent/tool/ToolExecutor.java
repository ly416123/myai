package com.example.agent.tool;

import com.example.agent.model.ToolCall;
import com.example.agent.workflow.FailureCategory;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具执行器：将模型生成的 ToolCall 安全地分发到本地 AgentTool。
 *
 * <p>执行顺序为：根据名称查找 → 按工具 Schema 校验 JSON 参数 → 在线程任务中执行 →
 * 在限定时间内等待 → 将成功或失败统一转换成 ToolResult。模型只提供“想调用什么、参数是什么”，
 * 不会直接执行任意 Java 代码。
 *
 * <p>它类似传统后端中带入参校验、路由和异常映射的应用服务。Spring AI 可以代为分发
 * ToolCallback，但本项目保留显式执行器，方便学习每一步的边界。
 */
public final class ToolExecutor implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);
    /** 工具名到实现的查询入口。 */
    private final ToolRegistry registry;
    /** 编译并缓存注册工具的 Draft 2020-12 JSON Schema。 */
    private final ToolInputSchemaValidator inputSchemaValidator = new ToolInputSchemaValidator();
    /** 单次工具执行最多等待的时间。超时会取消 Future 并返回受控失败结果。 */
    private final Duration timeout;
    /** 虚拟线程执行器，让阻塞工具不占用调用 Runtime 的线程。 */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * @param registry 已注册的工具集合
     * @param timeout 每个工具调用的等待上限
     */
    public ToolExecutor(ToolRegistry registry, Duration timeout) {
        this.registry = java.util.Objects.requireNonNull(registry);
        this.timeout = java.util.Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Tool timeout must be positive");
        registry.all().forEach(inputSchemaValidator::register);
    }

    /**
     * 查找、校验并执行一个工具调用。任何预期内的失败都转换为 ToolResult.failure。
     *
     * @param call 模型请求的工具名、调用 ID 和 JSON 参数
     * @return 与原调用 ID 对应的成功或失败结果
     */
    public ToolResult execute(ToolCall call) {
        return execute(call, null, null, timeout);
    }

    /** 带运行与步骤标识的执行入口，供 Workflow 关联可观测记录。 */
    public ToolResult execute(ToolCall call, String runId, String stepId) {
        return execute(call, runId, stepId, timeout);
    }

    /** 带任务剩余时限的入口，确保单个工具等待不会超过 Workflow Deadline。 */
    public ToolResult execute(ToolCall call, String runId, String stepId, Duration maxWait) {
        java.util.Objects.requireNonNull(call);
        java.util.Objects.requireNonNull(maxWait);
        var tool = registry.find(call.name());
        if (tool.isEmpty()) {
            // The name is model-controlled when lookup fails and may contain user data.
            log.warn("runId={} stepId={} tool=unregistered failureCategory={}",
                    runId, stepId, FailureCategory.TOOL_NOT_FOUND);
            return ToolResult.failure(call.id(), call.name(), "Tool is not registered",
                    FailureCategory.TOOL_NOT_FOUND);
        }
        AgentTool target = tool.get();
        String registeredToolName = target.name();
        Duration waitLimit = maxWait.compareTo(timeout) < 0 ? maxWait : timeout;
        Future<JsonNode> future = null;
        try {
            if (!inputSchemaValidator.isValid(target, call.arguments())) {
                log.warn("runId={} stepId={} tool={} failureCategory={}",
                        runId, stepId, registeredToolName, FailureCategory.TOOL_ARGUMENTS);
                return ToolResult.failure(call.id(), call.name(), "Tool arguments failed validation",
                        FailureCategory.TOOL_ARGUMENTS);
            }
            future = executor.submit(() -> target.execute(call.arguments()));
            JsonNode output = future.get(Math.max(1, waitLimit.toMillis()), TimeUnit.MILLISECONDS);
            return ToolResult.success(call.id(), call.name(), output);
        } catch (TimeoutException e) {
            if (future != null) future.cancel(true);
            log.warn("runId={} stepId={} tool={} failureCategory={} durationMs={}",
                    runId, stepId, registeredToolName, FailureCategory.TOOL_TIMEOUT_UNKNOWN, waitLimit.toMillis());
            return ToolResult.indeterminate(call.id(), call.name(),
                    "Tool timed out; the outcome may be unknown and the call was not replayed");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("runId={} stepId={} tool={} failureCategory={} exceptionType={}",
                    runId, stepId, registeredToolName, FailureCategory.TOOL_EXECUTION, cause.getClass().getSimpleName());
            return ToolResult.failure(call.id(), call.name(), "Tool execution failed",
                    FailureCategory.TOOL_EXECUTION);
        } catch (InterruptedException interrupted) {
            if (future != null) future.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("runId={} stepId={} tool={} failureCategory={}",
                    runId, stepId, registeredToolName, FailureCategory.TOOL_TIMEOUT_UNKNOWN);
            return ToolResult.indeterminate(call.id(), call.name(),
                    "Tool execution was interrupted; the outcome may be unknown and the call was not replayed");
        } catch (Exception e) {
            log.warn("runId={} stepId={} tool={} failureCategory={} exceptionType={}",
                    runId, stepId, registeredToolName, FailureCategory.TOOL_EXECUTION, e.getClass().getSimpleName());
            return ToolResult.failure(call.id(), call.name(), "Tool execution failed",
                    FailureCategory.TOOL_EXECUTION);
        }
    }

    /** 停止接收新工具任务，并中断仍在运行的任务。 */
    @Override public void close() { executor.shutdownNow(); }
}
