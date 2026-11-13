package com.example.agent.app;

import com.example.agent.workflow.InMemoryRunStore;
import com.example.agent.workflow.RunStatus;
import com.example.agent.workflow.RunType;
import org.junit.jupiter.api.Test;

import java.time.Duration;

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
}
