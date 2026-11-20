package com.example.agent.configuration;

import com.example.agent.evaluation.EvaluationSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentConfigurationServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test
    void createsReleaseOnlyAfterPassingGateAndActivatesWithExpectedVersion() {
        AgentConfigStore store = mock(AgentConfigStore.class);
        EvaluationReleaseGate gate = mock(EvaluationReleaseGate.class);
        AgentConfigurationService service = new AgentConfigurationService(store, gate, JSON);
        AgentConfigRevision revision = AgentConfigRevision.create(document(), JSON);
        EvaluationSnapshot candidate = evaluation("eval-candidate");
        EvaluationGateDecision pass = decision(revision, true);
        when(gate.evaluate(eq(candidate), isNull(), eq(revision.contentHash()))).thenReturn(pass);
        AgentConfigRelease release = service.createRelease(revision, candidate, null, null,
                null, "baseline", "operator", Instant.now());
        when(store.findRelease(release.releaseId())).thenReturn(java.util.Optional.of(release));
        when(store.isApproved(release.releaseId())).thenReturn(true);
        service.approveRelease(release.releaseId(), "approver", "reviewed evaluation", Instant.now());
        when(store.assignment("prod", "myai")).thenReturn(java.util.Optional.empty());
        when(store.assign(eq("prod"), eq("myai"), eq(release.releaseId()), isNull(), eq(0), eq(0L),
                eq(ReleaseAction.ACTIVATE), eq("operator"), eq("approved rollout"), any(Instant.class))).thenReturn(true);

        assertTrue(service.activate("prod", "myai", release.releaseId(), 0, "operator", "approved rollout"));
        verify(store).saveRevision(eq(revision), isNull(), eq("operator"), any(Instant.class));
        verify(store).saveGateAttempt(argThat(attempt -> attempt.revisionId().equals(revision.revisionId())
                && attempt.evaluationId().equals(candidate.run().evaluationId())
                && attempt.decision().path("passed").asBoolean()));
        verify(store).saveRelease(release);
        verify(store).approveRelease(argThat(approval -> approval.releaseId().equals(release.releaseId())
                && approval.actor().equals("approver")));
        verify(store).assign(eq("prod"), eq("myai"), eq(release.releaseId()), isNull(), eq(0), eq(0L),
                eq(ReleaseAction.ACTIVATE), eq("operator"), eq("approved rollout"), any(Instant.class));
    }

    @Test
    void refusesFailedGateAndNeverWritesRelease() {
        AgentConfigStore store = mock(AgentConfigStore.class);
        EvaluationReleaseGate gate = mock(EvaluationReleaseGate.class);
        AgentConfigurationService service = new AgentConfigurationService(store, gate, JSON);
        AgentConfigRevision revision = AgentConfigRevision.create(document(), JSON);
        EvaluationSnapshot candidate = evaluation("eval-candidate");
        when(gate.evaluate(eq(candidate), isNull(), eq(revision.contentHash())))
                .thenReturn(decision(revision, false));

        assertThrows(IllegalStateException.class, () -> service.createRelease(revision, candidate, null, null,
                null, "regressed", "operator", Instant.now()));
        verify(store).saveGateAttempt(argThat(attempt -> !attempt.decision().path("passed").asBoolean()));
        verify(store, never()).saveRelease(any());
    }

    @Test
    void refusesToCompareBaselineEvaluationNotPinnedToItsRelease() {
        AgentConfigStore store = mock(AgentConfigStore.class);
        EvaluationReleaseGate gate = mock(EvaluationReleaseGate.class);
        AgentConfigurationService service = new AgentConfigurationService(store, gate, JSON);
        AgentConfigRevision revision = AgentConfigRevision.create(document(), JSON);
        AgentConfigRelease baseline = new AgentConfigRelease("baseline-release", revision.revisionId(),
                revision.contentHash(), "eval-baseline", null, JSON.createObjectNode().put("passed", true),
                null, "baseline", "operator", Instant.now());
        assertThrows(IllegalArgumentException.class, () -> service.createRelease(revision, evaluation("eval-candidate"),
                baseline, evaluation("eval-baseline"), null, "candidate", "operator", Instant.now()));
        verifyNoInteractions(gate);
        verify(store, never()).saveRelease(any());
    }

    @Test
    void rejectsStaleActivationAndInvalidGrayPercentage() {
        AgentConfigStore store = mock(AgentConfigStore.class);
        AgentConfigurationService service = new AgentConfigurationService(store,
                mock(EvaluationReleaseGate.class), JSON);
        AgentConfigRevision revision = AgentConfigRevision.create(document(), JSON);
        AgentConfigRelease release = new AgentConfigRelease("release-ok", revision.revisionId(), revision.contentHash(),
                "eval-ok", null, JSON.createObjectNode().put("passed", true), null, "ok", "operator", Instant.now());
        when(store.findRelease("release-ok")).thenReturn(java.util.Optional.of(release));
        when(store.isApproved("release-ok")).thenReturn(true);
        when(store.assignment("prod", "myai")).thenReturn(java.util.Optional.empty());
        when(store.assign(anyString(), anyString(), anyString(), any(), anyInt(), anyLong(), any(), anyString(),
                anyString(), any())).thenReturn(false);

        assertFalse(service.activate("prod", "myai", "release-ok", 0, "operator", "switch"));
        assertThrows(IllegalArgumentException.class, () -> service.rollout("prod", "myai", "release-ok", 100,
                0, "operator", "invalid"));
    }

    @Test
    void doesNotActivatePassingButUnapprovedRelease() {
        AgentConfigStore store = mock(AgentConfigStore.class);
        AgentConfigurationService service = new AgentConfigurationService(store,
                mock(EvaluationReleaseGate.class), JSON);
        AgentConfigRevision revision = AgentConfigRevision.create(document(), JSON);
        AgentConfigRelease release = new AgentConfigRelease("release-awaiting-approval", revision.revisionId(),
                revision.contentHash(), "eval-ok", null, JSON.createObjectNode().put("passed", true),
                null, "test", "operator", Instant.now());
        when(store.findRelease(release.releaseId())).thenReturn(java.util.Optional.of(release));
        when(store.isApproved(release.releaseId())).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> service.activate("prod", "myai", release.releaseId(),
                0, "operator", "activate"));
        verify(store, never()).assign(anyString(), anyString(), anyString(), any(), anyInt(), anyLong(),
                any(), anyString(), anyString(), any());
    }

    private static EvaluationGateDecision decision(AgentConfigRevision revision, boolean passed) {
        return new EvaluationGateDecision(EvaluationReleaseGate.POLICY_VERSION, passed,
                passed ? "PASS" : "EVALUATOR_REGRESSION", "eval-candidate", null, revision.contentHash(),
                "dataset", "v1", "b".repeat(64), Map.of("outcome", "v1"), 1, 1,
                passed ? List.of() : List.of("outcome:case-1"));
    }

    private static AgentConfigDocument document() {
        return new AgentConfigDocument(1, "prompt", List.of(),
                new ModelSettings("deepseek", "deepseek-chat", Map.of(), "secret://deepseek/api-key"), Set.of(),
                Map.of(), new RuntimeSettings(5, 5, 1, 30_000, 5, 2));
    }

    private static EvaluationSnapshot evaluation(String id) {
        var identity = new com.example.agent.evaluation.EvaluationDatasetIdentity("dataset", "v1", "b".repeat(64), 1);
        var run = com.example.agent.evaluation.EvaluationRunRecord.created(id, identity, null, "single",
                "deepseek", "deepseek-chat", Map.of("outcome", "v1"), 5, 5, 1,
                Instant.now(), Instant.now().plusSeconds(60));
        return new EvaluationSnapshot(run, List.of());
    }
}
