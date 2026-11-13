package com.example.agent.workflow;

/** A fenced lease for one durable child task within a collaborative run. */
public record TaskLeaseToken(String runId, String taskId, String ownerId, long fencingToken) {
    public TaskLeaseToken {
        if (runId == null || runId.isBlank() || taskId == null || taskId.isBlank()
                || ownerId == null || ownerId.isBlank() || fencingToken < 1) {
            throw new IllegalArgumentException("Invalid child task lease token");
        }
    }
}
