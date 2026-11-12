package com.example.agent.runtime;

import com.example.agent.workflow.FailureCategory;

/** 面向调用方的脱敏运行失败；完整消息历史仍留在 RunStore 中供检查。 */
public final class AgentRunException extends RuntimeException {
    private final String runId;
    private final FailureCategory category;

    public AgentRunException(String runId, FailureCategory category) {
        super("Agent run " + runId + " failed: " + category);
        this.runId = runId;
        this.category = category;
    }

    public String runId() { return runId; }
    public FailureCategory category() { return category; }
}
