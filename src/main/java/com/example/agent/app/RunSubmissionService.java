package com.example.agent.app;

import com.example.agent.model.Message;
import com.example.agent.workflow.RunRecord;
import com.example.agent.workflow.RunStore;
import com.example.agent.workflow.RunType;
import com.example.agent.configuration.ActiveConfigurationProvider;
import com.example.agent.memory.MemoryContextProvider;

import java.time.Duration;
import java.util.UUID;

/** Application service for accepting work into the durable Run queue without executing it in the submitting process. */
public final class RunSubmissionService {
    private final RunStore store;
    private final Duration taskTimeout;
    private final RunSubmissionPolicy submissionPolicy;
    private final ActiveConfigurationProvider configurationProvider;
    private final String environment;
    private final String agentId;
    private final MemoryContextProvider memoryContextProvider;

    public RunSubmissionService(RunStore store, Duration taskTimeout) {
        this(store, taskTimeout, RunSubmissionPolicy.noAuthentication());
    }

    public RunSubmissionService(RunStore store, Duration taskTimeout, RunSubmissionPolicy submissionPolicy) {
        this(store, taskTimeout, submissionPolicy, null, null, null, MemoryContextProvider.empty());
    }

    public RunSubmissionService(RunStore store, Duration taskTimeout, RunSubmissionPolicy submissionPolicy,
                                ActiveConfigurationProvider configurationProvider, String environment, String agentId) {
        this(store, taskTimeout, submissionPolicy, configurationProvider, environment, agentId,
                MemoryContextProvider.empty());
    }

    public RunSubmissionService(RunStore store, Duration taskTimeout, RunSubmissionPolicy submissionPolicy,
                                ActiveConfigurationProvider configurationProvider, String environment, String agentId,
                                MemoryContextProvider memoryContextProvider) {
        if (store == null || taskTimeout == null || taskTimeout.isZero() || taskTimeout.isNegative()
                || submissionPolicy == null || memoryContextProvider == null || (configurationProvider != null
                && (environment == null || environment.isBlank() || agentId == null || agentId.isBlank()))) {
            throw new IllegalArgumentException("Invalid Run submission configuration");
        }
        this.store = store;
        this.taskTimeout = taskTimeout;
        this.submissionPolicy = submissionPolicy;
        this.configurationProvider = configurationProvider;
        this.environment = environment;
        this.agentId = agentId;
        this.memoryContextProvider = memoryContextProvider;
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
        if (configurationProvider != null) {
            run.setConfigurationSnapshot(configurationProvider.resolve(environment, agentId, run.runId()));
        }
        run.setMemoryContextSnapshot(memoryContextProvider.snapshot(normalized));
        run.addMessage(Message.user(normalized));
        store.save(run);
        return run;
    }
}
