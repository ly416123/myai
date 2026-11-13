package com.example.agent.workflow;

/** 持久化标识 Run 所属的执行流程，防止由错误的 Runtime 恢复检查点。 */
public enum RunType {
    SINGLE_AGENT,
    COLLABORATIVE
}
