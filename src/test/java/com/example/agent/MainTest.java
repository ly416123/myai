package com.example.agent;

import com.example.agent.collaboration.CollaborativeWorkflow;
import com.example.agent.runtime.AgentRuntime;
import com.example.agent.workflow.InMemoryRunStore;
import com.example.agent.workflow.RunRecord;
import com.example.agent.workflow.RunStatus;
import com.example.agent.app.RunSubmissionService;
import com.example.agent.evaluation.AgentEvaluationConfig;
import com.example.agent.evaluation.AgentEvaluationRunner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MainTest {
    @Test
    void onceModeRunsConfiguredTaskWithoutReadingStdin() {
        AgentRuntime runtime = mock(AgentRuntime.class);
        AgentProperties properties = new AgentProperties();
        properties.setExecutionMode("once");
        properties.setTask("  calculate 2 + 2  ");
        RunRecord result = RunRecord.create("test-run", "calculate 2 + 2", java.time.Duration.ofSeconds(1));
        result.setStatus(RunStatus.SUCCEEDED);
        result.setFinalAnswer("4");
        when(runtime.runTracked("calculate 2 + 2")).thenReturn(result);

        new Main(runtime, mock(CollaborativeWorkflow.class), new InMemoryRunStore(), properties,
                mock(RunSubmissionService.class), runId -> java.util.List.of(),
                mock(AgentEvaluationRunner.class), evaluationConfig())
                .run(new DefaultApplicationArguments(new String[0]));

        verify(runtime).runTracked("calculate 2 + 2");
    }

    @Test
    void onceModeRejectsMissingTaskWithoutStartingInteractivePrompt() {
        AgentProperties properties = new AgentProperties();
        properties.setExecutionMode("once");

        assertThrows(IllegalArgumentException.class, () -> new Main(mock(AgentRuntime.class),
                        mock(CollaborativeWorkflow.class), new InMemoryRunStore(), properties,
                        mock(RunSubmissionService.class), runId -> java.util.List.of(),
                        mock(AgentEvaluationRunner.class), evaluationConfig())
                .run(new DefaultApplicationArguments(new String[0])));
    }

    @Test
    void liveEvaluationRequiresExplicitEnableFlag() {
        AgentProperties properties = new AgentProperties();
        properties.setExecutionMode("eval");
        AgentEvaluationRunner runner = mock(AgentEvaluationRunner.class);

        assertThrows(IllegalStateException.class, () -> new Main(mock(AgentRuntime.class),
                        mock(CollaborativeWorkflow.class), new InMemoryRunStore(), properties,
                        mock(RunSubmissionService.class), runId -> java.util.List.of(), runner, evaluationConfig())
                .run(new DefaultApplicationArguments(new String[0])));
        org.mockito.Mockito.verifyNoInteractions(runner);
    }

    @Test
    void toolAuditCommandWithoutRunIdShowsUsageInsteadOfStartingAgentRun() throws Exception {
        AgentRuntime runtime = mock(AgentRuntime.class);
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream capturedOut = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setIn(new ByteArrayInputStream("/tool-audit\n/exit\n".getBytes(StandardCharsets.UTF_8)));
            System.setOut(capturedOut);

            new Main(runtime, mock(CollaborativeWorkflow.class), new InMemoryRunStore(), new AgentProperties(),
                    mock(RunSubmissionService.class), runId -> java.util.List.of(),
                    mock(AgentEvaluationRunner.class), evaluationConfig())
                    .run(new DefaultApplicationArguments(new String[0]));
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("用法：/tool-audit <runId>"));
        verifyNoInteractions(runtime);
    }

    @Test
    void unknownSlashCommandIsRejectedInsteadOfStartingAgentRun() throws Exception {
        AgentRuntime runtime = mock(AgentRuntime.class);
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream capturedOut = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setIn(new ByteArrayInputStream("/unkown-command\n/exit\n".getBytes(StandardCharsets.UTF_8)));
            System.setOut(capturedOut);

            new Main(runtime, mock(CollaborativeWorkflow.class), new InMemoryRunStore(), new AgentProperties(),
                    mock(RunSubmissionService.class), runId -> java.util.List.of(),
                    mock(AgentEvaluationRunner.class), evaluationConfig())
                    .run(new DefaultApplicationArguments(new String[0]));
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("未知命令"));
        verifyNoInteractions(runtime);
    }

    private static AgentEvaluationConfig evaluationConfig() {
        return new AgentEvaluationConfig(false, 1, 2, 1, java.time.Duration.ofMinutes(1),
                java.nio.file.Path.of("target/evaluations"), null, null);
    }
}
