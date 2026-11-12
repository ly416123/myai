package com.example.agent.workflow;

import java.time.Instant;

/** 一次状态迁移的时间记录；只保存类别，不保存可能泄露信息的异常文本。 */
public record RunStateChange(RunStatus status, Instant at, FailureCategory failureCategory) { }
