package com.example.agent.workflow;

/** 步骤状态；IN_PROGRESS 在恢复时需要按步骤类型采用不同策略。 */
public enum RunStepStatus { PENDING, IN_PROGRESS, SUCCEEDED, FAILED, INDETERMINATE }
