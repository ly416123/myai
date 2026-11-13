package com.example.agent.workflow;

import java.util.List;

/** Query port for sanitized tool-call lifecycle events. */
public interface ToolExecutionAuditStore {
    /** Returns lifecycle events for one Run in persistence order. */
    List<ToolExecutionAudit> findToolAudit(String runId);
}
