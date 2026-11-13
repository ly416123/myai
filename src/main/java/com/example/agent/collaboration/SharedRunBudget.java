package com.example.agent.collaboration;

import com.example.agent.workflow.RunRecord;
import com.example.agent.workflow.RunStore;
import com.example.agent.workflow.LeaseToken;
import com.example.agent.workflow.TaskLeaseToken;
import com.example.agent.workflow.BudgetKind;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** 父 Run 级原子预算；所有并行 Worker 和 Supervisor 调用共用同一实例。 */
public final class SharedRunBudget {
    private final RunRecord run;
    private final int maxModelCalls;
    private final int maxToolCalls;
    private final RunStore store;
    private final LeaseToken parentLease;

    public SharedRunBudget(RunRecord run, CollaborationConfig config, RunStore store, LeaseToken parentLease) {
        this.run = run;
        this.maxModelCalls = config.maxModelCalls();
        this.maxToolCalls = config.maxToolCalls();
        this.store = java.util.Objects.requireNonNull(store);
        this.parentLease = java.util.Objects.requireNonNull(parentLease);
    }

    public synchronized boolean reserveModelCall(String stepId, TaskLeaseToken taskLease) {
        boolean reserved = store.reserveBudget(parentLease, taskLease, BudgetKind.MODEL_CALL,
                List.of(stepId), maxModelCalls, Instant.now());
        refreshCounts();
        return reserved;
    }

    public synchronized boolean reserveToolCalls(Collection<String> stepIds, TaskLeaseToken taskLease) {
        boolean reserved = store.reserveBudget(parentLease, taskLease, BudgetKind.TOOL_CALL,
                stepIds, maxToolCalls, Instant.now());
        refreshCounts();
        return reserved;
    }

    public synchronized int remainingModelCalls() {
        refreshCounts();
        return Math.max(0, maxModelCalls - run.modelCalls());
    }

    private void refreshCounts() {
        store.find(run.runId()).ifPresent(latest -> {
            run.setModelCalls(latest.modelCalls());
            run.setToolCalls(latest.toolCalls());
        });
    }
}
