package com.example.agent.workflow;

import java.time.Instant;

/** Sanitized, append-only metadata for one persisted tool-step state transition. */
public record ToolExecutionAudit(long auditId, String runId, String stepId, String toolCallId,
                                String taskId, String workerId, String toolName,
                                RunStepStatus status, FailureCategory failureCategory, Instant occurredAt) { }
