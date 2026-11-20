package com.example.agent.configuration;

import com.example.agent.evaluation.EvaluationSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Application boundary for immutable revisions, gated releases and atomic environment assignments. */
public final class AgentConfigurationService {
    private final AgentConfigStore store;
    private final EvaluationReleaseGate gate;
    private final ObjectMapper mapper;

    public AgentConfigurationService(AgentConfigStore store, EvaluationReleaseGate gate, ObjectMapper mapper) {
        this.store = java.util.Objects.requireNonNull(store);
        this.gate = java.util.Objects.requireNonNull(gate);
        this.mapper = java.util.Objects.requireNonNull(mapper);
    }

    public AgentConfigRevision createRevision(AgentConfigDocument document, String sourceCommit,
                                               String actor, Instant now) {
        requireActor(actor);
        AgentConfigRevision revision = AgentConfigRevision.create(document, mapper);
        store.saveRevision(revision, sourceCommit, actor, now);
        return revision;
    }

    /** Saves the immutable revision first, then creates a Release only if the offline gate passes. */
    public AgentConfigRelease createRelease(AgentConfigRevision revision, EvaluationSnapshot candidate,
                                             AgentConfigRelease baselineRelease, EvaluationSnapshot baselineEvaluation,
                                             String sourceCommit, String releaseNotes, String actor, Instant now) {
        if (revision == null || candidate == null || now == null) throw new IllegalArgumentException("Revision, evaluation and time are required");
        requireActor(actor);
        store.saveRevision(revision, sourceCommit, actor, now);
        if (baselineRelease != null && (baselineEvaluation == null
                || !baselineRelease.evaluationId().equals(baselineEvaluation.run().evaluationId()))) {
            throw new IllegalArgumentException("Baseline Release must be paired with its recorded evaluation");
        }
        if (baselineRelease != null && !baselineRelease.contentHash().equals(baselineEvaluation.run().configurationHash())) {
            throw new IllegalArgumentException("Baseline evaluation does not match its immutable Release configuration");
        }
        if (baselineRelease != null && !baselineRelease.gateDecision().path("passed").asBoolean(false)) {
            throw new IllegalArgumentException("Baseline Release has no passing publication gate");
        }
        if (baselineRelease == null && baselineEvaluation != null) {
            throw new IllegalArgumentException("Baseline evaluation requires a baseline Release");
        }
        EvaluationGateDecision decision = gate.evaluate(candidate, baselineEvaluation, revision.contentHash());
        store.saveGateAttempt(new ConfigurationGateAttempt(0, revision.revisionId(),
                candidate.run().evaluationId(), baselineRelease == null ? null : baselineRelease.releaseId(),
                mapper.valueToTree(decision), actor, now));
        if (!decision.passed()) throw new IllegalStateException("Configuration Release rejected by evaluation gate: " + decision.reason());
        String notes = validateReleaseNotes(releaseNotes);
        AgentConfigRelease release = new AgentConfigRelease("rel-" + UUID.randomUUID(), revision.revisionId(),
                revision.contentHash(), candidate.run().evaluationId(), baselineRelease == null ? null
                : baselineRelease.releaseId(), mapper.valueToTree(decision), sourceCommit, notes, actor, now);
        store.saveRelease(release);
        return release;
    }

    public boolean activate(String environment, String agentId, String releaseId, long expectedVersion,
                           String actor, String reason) {
        AgentConfigRelease release = publishableRelease(releaseId);
        requireApproved(releaseId);
        validateAssignmentInput(environment, agentId, expectedVersion, actor, reason);
        return store.assign(environment, agentId, release.releaseId(), null, 0, expectedVersion,
                ReleaseAction.ACTIVATE, actor, reason, Instant.now());
    }

    public boolean rollout(String environment, String agentId, String candidateReleaseId, int percent,
                           long expectedVersion, String actor, String reason) {
        if (percent < 1 || percent > 99) throw new IllegalArgumentException("Gray rollout must be between 1 and 99 percent");
        AgentConfigRelease candidate = publishableRelease(candidateReleaseId);
        requireApproved(candidateReleaseId);
        validateAssignmentInput(environment, agentId, expectedVersion, actor, reason);
        ActiveConfigAssignment current = store.assignment(environment, agentId)
                .orElseThrow(() -> new IllegalStateException("An active Release is required before starting gray rollout"));
        if (current.version() != expectedVersion) return false;
        if (current.activeReleaseId().equals(candidate.releaseId())) throw new IllegalArgumentException("Candidate Release is already active");
        return store.assign(environment, agentId, current.activeReleaseId(), candidate.releaseId(), percent,
                expectedVersion, ReleaseAction.ROLLOUT, actor, reason, Instant.now());
    }

    public boolean rollback(String environment, String agentId, String targetReleaseId, long expectedVersion,
                           String actor, String reason) {
        AgentConfigRelease target = publishableRelease(targetReleaseId);
        requireApproved(targetReleaseId);
        validateAssignmentInput(environment, agentId, expectedVersion, actor, reason);
        ActiveConfigAssignment current = store.assignment(environment, agentId)
                .orElseThrow(() -> new IllegalStateException("There is no active Release to roll back"));
        if (current.version() != expectedVersion) return false;
        return store.assign(environment, agentId, target.releaseId(), null, 0, expectedVersion,
                ReleaseAction.ROLLBACK, actor, reason, Instant.now());
    }

    /** CAS-protected rollback of the current gray candidate to its stable assignment. */
    public boolean automaticRollback(String environment, String agentId, ActiveConfigAssignment expected,
                                     String reasonActor, String reason, Instant at) {
        if (expected == null || expected.candidateReleaseId() == null || at == null) return false;
        publishableRelease(expected.activeReleaseId());
        requireApproved(expected.activeReleaseId());
        validateAssignmentInput(environment, agentId, expected.version(), reasonActor, reason);
        ActiveConfigAssignment latest = store.assignment(environment, agentId).orElse(null);
        if (latest == null || latest.version() != expected.version()
                || !latest.activeReleaseId().equals(expected.activeReleaseId())
                || !java.util.Objects.equals(latest.candidateReleaseId(), expected.candidateReleaseId())) return false;
        return store.assign(environment, agentId, latest.activeReleaseId(), null, 0, latest.version(),
                ReleaseAction.AUTO_ROLLBACK, reasonActor, reason, at);
    }

    public ActiveConfigAssignment current(String environment, String agentId) {
        return store.assignment(environment, agentId).orElse(null);
    }

    public List<AgentConfigRevision> revisions(int limit) { return store.listRevisions(limit); }
    public List<AgentConfigRelease> releases(int limit) { return store.listReleases(limit); }
    public List<ConfigDifference> diff(String firstRevisionId, String secondRevisionId) {
        AgentConfigRevision first = revision(firstRevisionId);
        AgentConfigRevision second = revision(secondRevisionId);
        var left = mapper.valueToTree(first.document());
        var right = mapper.valueToTree(second.document());
        var differences = new java.util.ArrayList<ConfigDifference>();
        collectDifferences("", left, right, differences);
        return List.copyOf(differences);
    }
    public List<ReleaseEvent> history(String environment, String agentId, int limit) {
        return store.events(environment, agentId, limit);
    }

    public List<ConfigurationGateAttempt> gateAttempts(int limit) { return store.gateAttempts(limit); }

    public ReleaseApproval approveRelease(String releaseId, String actor, String reason, Instant at) {
        publishableRelease(releaseId);
        requireActor(actor);
        if (reason == null || reason.isBlank() || reason.length() > 1000)
            throw new IllegalArgumentException("Release approval reason is invalid");
        var approval = new ReleaseApproval(releaseId, actor, reason, java.util.Objects.requireNonNull(at));
        store.approveRelease(approval);
        return approval;
    }

    public boolean isApproved(String releaseId) { return store.isApproved(releaseId); }

    public AgentConfigRevision revision(String revisionId) {
        return store.findRevision(revisionId).orElseThrow(() -> new IllegalArgumentException("Configuration revision not found"));
    }

    public AgentConfigRelease release(String releaseId) {
        return store.findRelease(releaseId).orElseThrow(() -> new IllegalArgumentException("Configuration Release not found"));
    }

    private AgentConfigRelease publishableRelease(String releaseId) {
        AgentConfigRelease release = release(releaseId);
        if (!release.gateDecision().path("passed").asBoolean(false)) {
            throw new IllegalStateException("Configuration Release has no passing evaluation gate");
        }
        return release;
    }

    private void requireApproved(String releaseId) {
        if (!store.isApproved(releaseId)) throw new IllegalStateException("Configuration Release requires operator approval");
    }

    private static void validateAssignmentInput(String environment, String agentId, long expectedVersion,
                                                String actor, String reason) {
        if (environment == null || environment.isBlank() || agentId == null || agentId.isBlank()
                || expectedVersion < 0 || reason == null || reason.isBlank() || reason.length() > 1000) {
            throw new IllegalArgumentException("Configuration assignment input is invalid");
        }
        if (hasCredentialLiteral(reason)) throw new IllegalArgumentException("Configuration audit reason contains credential-like content");
        requireActor(actor);
    }

    private static String validateReleaseNotes(String notes) {
        if (notes == null || notes.length() > 2000 || hasCredentialLiteral(notes)) {
            throw new IllegalArgumentException("Release notes are invalid or contain credential-like content");
        }
        return notes;
    }

    private static boolean hasCredentialLiteral(String value) {
        try {
            ConfigurationSecretGuard.rejectCredentialLiteral(value, "Audit text");
            return false;
        } catch (IllegalArgumentException rejected) {
            return true;
        }
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank() || actor.length() > 120) throw new IllegalArgumentException("Release actor is required");
    }

    private void collectDifferences(String path, com.fasterxml.jackson.databind.JsonNode before,
                                    com.fasterxml.jackson.databind.JsonNode after,
                                    List<ConfigDifference> differences) {
        if (java.util.Objects.equals(before, after)) return;
        if (before != null && after != null && before.isObject() && after.isObject()) {
            var names = new java.util.TreeSet<String>();
            before.fieldNames().forEachRemaining(names::add);
            after.fieldNames().forEachRemaining(names::add);
            for (String name : names) collectDifferences(path.isEmpty() ? name : path + "." + name,
                    before.has(name) ? before.get(name) : null, after.has(name) ? after.get(name) : null, differences);
            return;
        }
        if (before != null && after != null && before.isArray() && after.isArray()) {
            int size = Math.max(before.size(), after.size());
            for (int index = 0; index < size; index++) collectDifferences(path + "[" + index + "]",
                    index < before.size() ? before.get(index) : null,
                    index < after.size() ? after.get(index) : null, differences);
            return;
        }
        boolean redact = path.equals("systemPrompt") || path.startsWith("skills[") && path.endsWith(".content")
                || path.endsWith(".credentialRef");
        differences.add(new ConfigDifference(path, redact ? "<redacted>" : display(before),
                redact ? "<redacted>" : display(after)));
    }

    private static String display(com.fasterxml.jackson.databind.JsonNode value) {
        if (value == null) return "<absent>";
        return value.isTextual() ? value.textValue() : value.toString();
    }
}
