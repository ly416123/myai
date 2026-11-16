package com.example.agent.app;

import com.example.agent.workflow.InMemoryRunStore;
import com.example.agent.workflow.RunStatus;
import com.example.agent.workflow.RunType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RunSubmissionServiceTest {
    @Test
    void persistsSingleAndCollaborativeRunsForBackgroundWorkers() {
        InMemoryRunStore store = new InMemoryRunStore();
        RunSubmissionService submissions = new RunSubmissionService(store, Duration.ofMinutes(3));

        var single = submissions.submitSingle("  summarize report  ");
        var collaborative = submissions.submitCollaborative("compare these options");

        assertEquals(RunStatus.CREATED, single.status());
        assertEquals(RunType.SINGLE_AGENT, single.runType());
        assertEquals("summarize report", single.userInput());
        assertEquals(RunStatus.CREATED, collaborative.status());
        assertEquals(RunType.COLLABORATIVE, collaborative.runType());
        assertEquals(single.runId(), store.find(single.runId()).orElseThrow().runId());
        assertEquals("compare these options", store.find(collaborative.runId()).orElseThrow().userInput());
    }

    @Test
    void rejectsBlankWorkBeforeWritingACheckpoint() {
        InMemoryRunStore store = new InMemoryRunStore();
        RunSubmissionService submissions = new RunSubmissionService(store, Duration.ofMinutes(3));

        assertThrows(IllegalArgumentException.class, () -> submissions.submitSingle(" \n "));
        assertTrue(store.list().isEmpty());
    }

    @Test
    void passesCallerContextToSubmissionPolicyBeforePersistingRun() {
        InMemoryRunStore store = new InMemoryRunStore();
        var observed = new AtomicReference<CallerContext>();
        var submissions = new RunSubmissionService(store, Duration.ofMinutes(3), (caller, type) -> {
            assertEquals(RunType.SINGLE_AGENT, type);
            observed.set(caller);
        });
        var caller = new CallerContext("test-user-42");

        var run = submissions.submitSingle("summarize report", caller);

        assertEquals(caller, observed.get());
        assertEquals(run.runId(), store.find(run.runId()).orElseThrow().runId());
    }

    @Test
    void rejectedCallerDoesNotPersistRun() {
        InMemoryRunStore store = new InMemoryRunStore();
        var submissions = new RunSubmissionService(store, Duration.ofMinutes(3), (caller, type) -> {
            throw new IllegalArgumentException("submission rejected");
        });

        assertThrows(IllegalArgumentException.class,
                () -> submissions.submitSingle("summarize report", new CallerContext("test-user-42")));
        assertTrue(store.list().isEmpty());
    }
}
