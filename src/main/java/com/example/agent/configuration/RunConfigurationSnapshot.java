package com.example.agent.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Immutable, hash-verified configuration pinned to one Run for execution and recovery. */
public record RunConfigurationSnapshot(String releaseId, String revisionId, String contentHash,
                                       AgentConfigDocument document) {
    private static final ObjectMapper HASH_MAPPER = new ObjectMapper().findAndRegisterModules();

    @JsonCreator
    public RunConfigurationSnapshot(@JsonProperty("releaseId") String releaseId,
                                    @JsonProperty("revisionId") String revisionId,
                                    @JsonProperty("contentHash") String contentHash,
                                    @JsonProperty("document") AgentConfigDocument document) {
        if (releaseId == null || releaseId.isBlank() || releaseId.length() > 120
                || revisionId == null || contentHash == null || document == null
                || !contentHash.matches("[a-f0-9]{64}") || !revisionId.equals("rev-" + contentHash)) {
            throw new IllegalArgumentException("Run configuration snapshot identity is invalid");
        }
        AgentConfigRevision calculated = AgentConfigRevision.create(document, HASH_MAPPER);
        if (!calculated.contentHash().equals(contentHash) || !calculated.revisionId().equals(revisionId)) {
            throw new IllegalArgumentException("Run configuration snapshot content hash does not match its document");
        }
        this.releaseId = releaseId;
        this.revisionId = revisionId;
        this.contentHash = contentHash;
        this.document = document;
    }

    public static RunConfigurationSnapshot from(AgentConfigRelease release, AgentConfigRevision revision) {
        if (release == null || revision == null || !release.revisionId().equals(revision.revisionId())
                || !release.contentHash().equals(revision.contentHash())) {
            throw new IllegalArgumentException("Release and configuration revision do not match");
        }
        return new RunConfigurationSnapshot(release.releaseId(), revision.revisionId(), revision.contentHash(),
                revision.document());
    }

    /** Builds a non-activatable, clearly marked snapshot for an offline candidate evaluation. */
    public static RunConfigurationSnapshot forEvaluation(AgentConfigRevision revision) {
        if (revision == null) throw new IllegalArgumentException("Configuration revision is required");
        return new RunConfigurationSnapshot("candidate-" + revision.contentHash(), revision.revisionId(),
                revision.contentHash(), revision.document());
    }
}
