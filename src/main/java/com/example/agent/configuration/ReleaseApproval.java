package com.example.agent.configuration;

import java.time.Instant;

/** Immutable record that an operator approved a gate-passing Release for activation. */
public record ReleaseApproval(String releaseId, String actor, String reason, Instant approvedAt) {
    public ReleaseApproval {
        if (releaseId == null || releaseId.isBlank() || actor == null || actor.isBlank()
                || reason == null || reason.isBlank() || reason.length() > 1000 || approvedAt == null)
            throw new IllegalArgumentException("Release approval is invalid");
        ConfigurationSecretGuard.rejectCredentialLiteral(reason, "Approval reason");
    }
}
