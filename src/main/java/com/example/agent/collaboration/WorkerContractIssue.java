package com.example.agent.collaboration;

/** 可安全持久化的契约校验问题；只记录字段/来源/代码，不包含输入值。 */
public record WorkerContractIssue(String field, String source, String reasonCode) { }
