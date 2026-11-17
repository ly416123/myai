package com.example.agent.configuration;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable storage boundary for immutable Agent configuration revisions and releases. */
public interface AgentConfigStore {
    void saveRevision(AgentConfigRevision revision, String sourceCommit, String createdBy, Instant createdAt);
    Optional<AgentConfigRevision> findRevision(String revisionId);
    List<AgentConfigRevision> listRevisions(int limit);
    void saveRelease(AgentConfigRelease release);
    Optional<AgentConfigRelease> findRelease(String releaseId);
    List<AgentConfigRelease> listReleases(int limit);
    Optional<ActiveConfigAssignment> assignment(String environment, String agentId);
    boolean assign(String environment, String agentId, String activeReleaseId, String candidateReleaseId,
                   int candidatePercent, long expectedVersion, ReleaseAction action, String actor,
                   String reason, Instant at);
    List<ReleaseEvent> events(String environment, String agentId, int limit);
    void saveGateAttempt(ConfigurationGateAttempt attempt);
    List<ConfigurationGateAttempt> gateAttempts(int limit);
    void approveRelease(ReleaseApproval approval);
    boolean isApproved(String releaseId);
}
