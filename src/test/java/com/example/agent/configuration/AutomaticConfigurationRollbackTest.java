package com.example.agent.configuration;

import com.example.agent.workflow.InMemoryRunStore;
import com.example.agent.workflow.RunRecord;
import com.example.agent.workflow.RunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutomaticConfigurationRollbackTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Instant NOW = Instant.parse("2026-11-17T00:00:00Z");

    @Test
    void rollsBackCandidateWhenMinimumSampleAndFailureThresholdAreMet() {
        var configStore = mock(AgentConfigStore.class);
        var service = new AgentConfigurationService(configStore, mock(EvaluationReleaseGate.class), JSON);
        var runs = new InMemoryRunStore();
        var stableRevision = revision("stable prompt");
        var candidateRevision = revision("candidate prompt");
        var stable = release("stable", stableRevision);
        var candidate = release("candidate", candidateRevision);
        var assignment = assignment("stable", "candidate", 25, 4, NOW.minusSeconds(300));
        when(configStore.assignment("prod", "myai")).thenReturn(Optional.of(assignment));
        when(configStore.events("prod", "myai", 500)).thenReturn(List.of());
        when(configStore.findRelease("stable")).thenReturn(Optional.of(stable));
        when(configStore.isApproved("stable")).thenReturn(true);
        when(configStore.assign(eq("prod"), eq("myai"), eq("stable"), isNull(), eq(0), eq(4L),
                eq(ReleaseAction.AUTO_ROLLBACK), eq("system-auto-rollback"), contains("candidate"), any(Instant.class)))
                .thenReturn(true);
        runs.save(run("candidate-1", candidate, candidateRevision, RunStatus.FAILED, NOW.minusSeconds(120)));
        runs.save(run("candidate-2", candidate, candidateRevision, RunStatus.FAILED, NOW.minusSeconds(90)));
        runs.save(run("candidate-3", candidate, candidateRevision, RunStatus.SUCCEEDED, NOW.minusSeconds(60)));
        runs.save(run("stable-1", stable, stableRevision, RunStatus.FAILED, NOW.minusSeconds(30)));

        var policy = new AutomaticRollbackPolicy(true, 3, 0.6, Duration.ofMinutes(10),
                Duration.ofMinutes(5), 1000);
        var rollback = new AutomaticConfigurationRollback(service, runs, policy,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var result = rollback.evaluate("prod", "myai");

        assertEquals(AutomaticRollbackOutcome.ROLLED_BACK, result.outcome());
        assertEquals(3, result.samples());
        assertEquals(2, result.failures());
        assertEquals(2.0 / 3, result.failureRate(), 0.0001);
        verify(configStore).assign(eq("prod"), eq("myai"), eq("stable"), isNull(), eq(0), eq(4L),
                eq(ReleaseAction.AUTO_ROLLBACK), eq("system-auto-rollback"), contains("candidate"), any(Instant.class));
    }

    @Test
    void doesNotDecideWithInsufficientSamplesAndHonorsCooldown() {
        var configStore = mock(AgentConfigStore.class);
        var service = new AgentConfigurationService(configStore, mock(EvaluationReleaseGate.class), JSON);
        var runs = new InMemoryRunStore();
        var candidateRevision = revision("candidate");
        var candidate = release("candidate", candidateRevision);
        when(configStore.assignment("prod", "myai")).thenReturn(Optional.of(
                assignment("stable", "candidate", 10, 2, NOW.minusSeconds(300))));
        runs.save(run("one", candidate, candidateRevision, RunStatus.FAILED, NOW.minusSeconds(10)));
        var policy = new AutomaticRollbackPolicy(true, 2, 0.5, Duration.ofMinutes(10),
                Duration.ofMinutes(5), 1000);
        var rollback = new AutomaticConfigurationRollback(service, runs, policy, Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(AutomaticRollbackOutcome.INSUFFICIENT_SAMPLES, rollback.evaluate("prod", "myai").outcome());

        when(configStore.events("prod", "myai", 500)).thenReturn(List.of(new ReleaseEvent(9, "prod", "myai",
                ReleaseAction.AUTO_ROLLBACK, "stable", "system-auto-rollback", "previous rollback",
                NOW.minusSeconds(60))));
        runs.save(run("two", candidate, candidateRevision, RunStatus.FAILED, NOW.minusSeconds(5)));
        assertEquals(AutomaticRollbackOutcome.COOLDOWN, rollback.evaluate("prod", "myai").outcome());
        verify(configStore, never()).assign(anyString(), anyString(), anyString(), any(), anyInt(), anyLong(),
                any(), anyString(), anyString(), any());
    }

    @Test
    void ignoresCancelledRunsAndDoesNotRollbackWhenFailureRateIsBelowThreshold() {
        var configStore = mock(AgentConfigStore.class);
        var service = new AgentConfigurationService(configStore, mock(EvaluationReleaseGate.class), JSON);
        var runs = new InMemoryRunStore();
        var candidateRevision = revision("candidate");
        var candidate = release("candidate", candidateRevision);
        when(configStore.assignment("prod", "myai")).thenReturn(Optional.of(
                assignment("stable", "candidate", 10, 3, NOW.minusSeconds(300))));
        runs.save(run("success", candidate, candidateRevision, RunStatus.SUCCEEDED, NOW.minusSeconds(30)));
        runs.save(run("failed", candidate, candidateRevision, RunStatus.FAILED, NOW.minusSeconds(20)));
        runs.save(run("cancelled", candidate, candidateRevision, RunStatus.CANCELLED, NOW.minusSeconds(10)));
        var rollback = new AutomaticConfigurationRollback(service, runs,
                new AutomaticRollbackPolicy(true, 2, 0.6, Duration.ofMinutes(10), Duration.ofMinutes(5), 1000),
                Clock.fixed(NOW, ZoneOffset.UTC));
        var result = rollback.evaluate("prod", "myai");
        assertEquals(AutomaticRollbackOutcome.THRESHOLD_NOT_MET, result.outcome());
        assertEquals(2, result.samples());
        verify(configStore, never()).assign(anyString(), anyString(), anyString(), any(), anyInt(), anyLong(),
                any(), anyString(), anyString(), any());
    }

    @Test
    void includesWindowStartButExcludesRunsOutsideWindowAndFutureRuns() {
        var configStore = mock(AgentConfigStore.class);
        var service = new AgentConfigurationService(configStore, mock(EvaluationReleaseGate.class), JSON);
        var runs = new InMemoryRunStore();
        var stableRevision = revision("stable boundary");
        var candidateRevision = revision("candidate boundary");
        var stable = release("stable-boundary", stableRevision);
        var candidate = release("candidate-boundary", candidateRevision);
        var assignment = assignment("stable-boundary", "candidate-boundary", 50, 8, NOW.minusSeconds(600));
        when(configStore.assignment("prod", "myai")).thenReturn(Optional.of(assignment));
        when(configStore.events("prod", "myai", 500)).thenReturn(List.of());
        when(configStore.findRelease(stable.releaseId())).thenReturn(Optional.of(stable));
        when(configStore.isApproved(stable.releaseId())).thenReturn(true);
        when(configStore.assign(eq("prod"), eq("myai"), eq(stable.releaseId()), isNull(), eq(0), eq(8L),
                eq(ReleaseAction.AUTO_ROLLBACK), anyString(), anyString(), any(Instant.class))).thenReturn(true);
        runs.save(run("at-window-start", candidate, candidateRevision, RunStatus.FAILED, NOW.minusSeconds(300)));
        runs.save(run("before-window", candidate, candidateRevision, RunStatus.FAILED, NOW.minusSeconds(301)));
        runs.save(run("future", candidate, candidateRevision, RunStatus.SUCCEEDED, NOW.plusSeconds(1)));
        var rollback = new AutomaticConfigurationRollback(service, runs,
                new AutomaticRollbackPolicy(true, 1, 1, Duration.ofMinutes(5), Duration.ZERO, 20),
                Clock.fixed(NOW, ZoneOffset.UTC));

        var result = rollback.evaluate("prod", "myai");

        assertEquals(AutomaticRollbackOutcome.ROLLED_BACK, result.outcome());
        assertEquals(1, result.samples());
        assertEquals(1, result.failures());
    }

    @Test
    void doesNotDecideWhenBoundedRecentRunPageCouldBeTruncated() {
        var configStore = mock(AgentConfigStore.class);
        var service = new AgentConfigurationService(configStore, mock(EvaluationReleaseGate.class), JSON);
        when(configStore.assignment("prod", "myai")).thenReturn(Optional.of(
                assignment("stable", "candidate", 50, 1, NOW.minusSeconds(10))));
        when(configStore.events("prod", "myai", 500)).thenReturn(List.of());
        var runs = new InMemoryRunStore();
        runs.save(RunRecord.create("full-page-1", "one", Duration.ofMinutes(1)));
        runs.save(RunRecord.create("full-page-2", "two", Duration.ofMinutes(1)));
        var rollback = new AutomaticConfigurationRollback(service, runs,
                new AutomaticRollbackPolicy(true, 1, 0.5, Duration.ofMinutes(5), Duration.ZERO, 2),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(AutomaticRollbackOutcome.SAMPLE_SCAN_LIMIT, rollback.evaluate("prod", "myai").outcome());
        verify(configStore, never()).assign(anyString(), anyString(), anyString(), any(), anyInt(), anyLong(),
                any(), anyString(), anyString(), any());
    }

    private static RunRecord run(String id, AgentConfigRelease release, AgentConfigRevision revision,
                                 RunStatus status, Instant completedAt) {
        var run = RunRecord.create(id, "input", Duration.ofMinutes(5));
        run.transitionTo(RunStatus.RUNNING, completedAt.minusSeconds(1), null);
        run.setConfigurationSnapshot(RunConfigurationSnapshot.from(release, revision));
        run.transitionTo(status, completedAt, null);
        return run;
    }

    private static AgentConfigRevision revision(String prompt) {
        return AgentConfigRevision.create(new AgentConfigDocument(1, prompt, List.of(),
                new ModelSettings("deepseek", "flash", java.util.Map.of(), "secret://provider/key"),
                java.util.Set.of(), java.util.Map.of(), new RuntimeSettings(5, 5, 1, 10000, 5, 2)), JSON);
    }

    private static AgentConfigRelease release(String id, AgentConfigRevision revision) {
        return new AgentConfigRelease(id, revision.revisionId(), revision.contentHash(), "eval-1", null,
                JSON.createObjectNode().put("passed", true), null, "test", "operator", NOW.minusSeconds(600));
    }

    private static ActiveConfigAssignment assignment(String active, String candidate, int percent, long version,
                                                     Instant updatedAt) {
        return new ActiveConfigAssignment("prod", "myai", active, candidate, percent, version, updatedAt);
    }
}
