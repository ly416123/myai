package com.example.agent.app;

import com.example.agent.model.Message;
import com.example.agent.workflow.RunRecord;
import com.example.agent.workflow.RunStore;
import com.example.agent.workflow.RunType;

import java.time.Duration;
import java.util.UUID;

/** Application service for accepting work into the durable Run queue without executing it in the submitting process. */
public final class RunSubmissionService {
    private final RunStore store;
    private final Duration taskTimeout;

    public RunSubmissionService(RunStore store, Duration taskTimeout) {
        if (store == null || taskTimeout == null || taskTimeout.isZero() || taskTimeout.isNegative()) {
            throw new IllegalArgumentException("Invalid Run submission configuration");
        }
        this.store = store;
        this.taskTimeout = taskTimeout;
    }

    public RunRecord submitSingle(String input) {
        return submit(input, RunType.SINGLE_AGENT);
    }

    public RunRecord submitCollaborative(String input) {
        return submit(input, RunType.COLLABORATIVE);
    }

    private RunRecord submit(String input, RunType type) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("Task input must not be blank");
        String normalized = input.trim();
        RunRecord run = RunRecord.create(UUID.randomUUID().toString(), normalized, taskTimeout, type);
        run.addMessage(Message.user(normalized));
        store.save(run);
        return run;
    }
}
