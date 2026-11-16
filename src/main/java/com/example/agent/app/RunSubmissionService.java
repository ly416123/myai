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
    private final RunSubmissionPolicy submissionPolicy;

    public RunSubmissionService(RunStore store, Duration taskTimeout) {
        this(store, taskTimeout, RunSubmissionPolicy.noAuthentication());
    }

    public RunSubmissionService(RunStore store, Duration taskTimeout, RunSubmissionPolicy submissionPolicy) {
        if (store == null || taskTimeout == null || taskTimeout.isZero() || taskTimeout.isNegative()
                || submissionPolicy == null) {
            throw new IllegalArgumentException("Invalid Run submission configuration");
        }
        this.store = store;
        this.taskTimeout = taskTimeout;
        this.submissionPolicy = submissionPolicy;
    }

    public RunRecord submitSingle(String input) {
        return submitSingle(input, CallerContext.localCli());
    }

    public RunRecord submitSingle(String input, CallerContext caller) {
        return submit(input, RunType.SINGLE_AGENT, caller);
    }

    public RunRecord submitCollaborative(String input) {
        return submitCollaborative(input, CallerContext.localCli());
    }

    public RunRecord submitCollaborative(String input, CallerContext caller) {
        return submit(input, RunType.COLLABORATIVE, caller);
    }

    private RunRecord submit(String input, RunType type, CallerContext caller) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("Task input must not be blank");
        if (caller == null) throw new IllegalArgumentException("Caller context must be provided");
        submissionPolicy.validate(caller, type);
        String normalized = input.trim();
        RunRecord run = RunRecord.create(UUID.randomUUID().toString(), normalized, taskTimeout, type);
        run.addMessage(Message.user(normalized));
        store.save(run);
        return run;
    }
}
