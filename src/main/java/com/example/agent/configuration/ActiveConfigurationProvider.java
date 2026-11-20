package com.example.agent.configuration;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/** Resolves an immutable release once for a newly created Run; missing or corrupt state fails closed. */
public final class ActiveConfigurationProvider {
    private final AgentConfigStore store;

    public ActiveConfigurationProvider(AgentConfigStore store) {
        this.store = java.util.Objects.requireNonNull(store);
    }

    public RunConfigurationSnapshot resolve(String environment, String agentId, String runId) {
        if (environment == null || environment.isBlank() || agentId == null || agentId.isBlank()
                || runId == null || runId.isBlank()) throw new IllegalArgumentException("Configuration lookup keys are required");
        ActiveConfigAssignment assignment = store.assignment(environment, agentId)
                .orElseThrow(() -> new IllegalStateException("No active Agent configuration is assigned"));
        String selected = selectRelease(assignment, runId);
        AgentConfigRelease release = store.findRelease(selected)
                .orElseThrow(() -> new IllegalStateException("Assigned Agent configuration release is missing"));
        AgentConfigRevision revision = store.findRevision(release.revisionId())
                .orElseThrow(() -> new IllegalStateException("Assigned Agent configuration revision is missing"));
        return RunConfigurationSnapshot.from(release, revision);
    }

    static String selectRelease(ActiveConfigAssignment assignment, String runId) {
        if (assignment.candidateReleaseId() == null) return assignment.activeReleaseId();
        int bucket = stableBucket(runId);
        return bucket < assignment.candidatePercent() ? assignment.candidateReleaseId() : assignment.activeReleaseId();
    }

    private static int stableBucket(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Math.floorMod(ByteBuffer.wrap(digest).getInt(), 100);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
