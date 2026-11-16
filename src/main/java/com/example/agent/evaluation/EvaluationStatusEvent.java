package com.example.agent.evaluation;

import java.time.Instant;

public record EvaluationStatusEvent(EvaluationRunStatus status, Instant occurredAt, String failureCategory) { }
