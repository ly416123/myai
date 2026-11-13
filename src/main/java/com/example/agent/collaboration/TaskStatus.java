package com.example.agent.collaboration;

/** 父 Run 内子任务状态。INDETERMINATE 不会自动重放。 */
public enum TaskStatus { PENDING, IN_PROGRESS, SUCCEEDED, FAILED, SKIPPED, INDETERMINATE }
