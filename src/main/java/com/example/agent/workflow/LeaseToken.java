package com.example.agent.workflow;

/** 单次执行租约令牌；fencingToken 每次重新认领都会单调递增。 */
public record LeaseToken(String runId, String ownerId, long fencingToken) { }
