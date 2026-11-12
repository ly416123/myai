package com.example.agent.workflow;

import java.time.Duration;
import java.time.Instant;

/**
 * 运行记录中的一个模型或工具步骤。
 * 不包含消息正文、工具参数或异常原文，避免把敏感数据放进可观测记录。
 */
public record RunStep(
        String stepId,
        StepKind kind,
        RunStepStatus status,
        Instant startedAt,
        Instant completedAt,
        long durationMillis,
        String toolCallId,
        String toolName,
        FailureCategory failureCategory,
        Long promptTokens,
        Long completionTokens) {

    public static RunStep modelStarted(String stepId, Instant now) {
        return new RunStep(stepId, StepKind.MODEL, RunStepStatus.IN_PROGRESS, now, null, 0,
                null, null, null, null, null);
    }

    public static RunStep toolPending(String stepId, String callId, String toolName, Instant now) {
        return new RunStep(stepId, StepKind.TOOL, RunStepStatus.PENDING, now, null, 0,
                callId, toolName, null, null, null);
    }

    public RunStep start(Instant now) {
        return new RunStep(stepId, kind, RunStepStatus.IN_PROGRESS, now, null, 0,
                toolCallId, toolName, null, null, null);
    }

    public RunStep finish(RunStepStatus finalStatus, Instant now, FailureCategory category,
                          Long promptTokenCount, Long completionTokenCount) {
        long elapsed = Math.max(0, Duration.between(startedAt, now).toMillis());
        return new RunStep(stepId, kind, finalStatus, startedAt, now, elapsed,
                toolCallId, toolName, category, promptTokenCount, completionTokenCount);
    }
}
