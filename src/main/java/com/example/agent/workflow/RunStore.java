package com.example.agent.workflow;

import java.util.List;
import java.util.Optional;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import com.example.agent.collaboration.ChildTaskRecord;

/** 保存和读取 Workflow 检查点的存储抽象。 */
public interface RunStore {
    void save(RunRecord run);
    Optional<RunRecord> find(String runId);
    List<RunRecord> list();

    /** Returns a bounded page of the newest Runs, ordered by creation time descending. */
    List<RunRecord> listRecent(int limit);

    /** Returns aggregate dispatch backlog and expired-lease state without exposing Run payloads. */
    RunQueueSnapshot queueSnapshot(Instant now);

    /** Returns a bounded batch of unfinished Runs whose execution lease is absent or expired. */
    List<RunRecord> findRecoverable(Instant now, int limit);

    /** Returns collaborative Runs with a live supervisor lease and unfinished child tasks. */
    List<RunRecord> findActiveCollaborativeRuns(Instant now, int limit);

    /** Deletes a bounded batch of expired terminal Runs and their owned checkpoint projections. */
    int purgeTerminalRunsBefore(Instant cutoff, int limit);

    /** 原子认领尚未终结且没有有效租约的 Run。 */
    Optional<LeaseToken> claim(String runId, String ownerId, Instant now, Duration leaseDuration);

    /** 仅当令牌仍持有有效租约时保存检查点。 */
    boolean saveFenced(RunRecord run, LeaseToken token, Instant now);

    /** 仅当前租约所有者可续租。 */
    boolean renew(LeaseToken token, Instant now, Duration leaseDuration);

    /** 持久化取消请求；由当前租约持有者观察并完成终态迁移。 */
    boolean requestCancellation(String runId, Instant requestedAt);

    /** Atomically claim a planned child task while the caller holds its parent Run lease. */
    Optional<TaskLeaseToken> claimChildTask(LeaseToken parentLease, String taskId, String ownerId,
                                            Instant now, Duration leaseDuration);

    /** Confirms that neither the task lease nor its parent Run lease has been fenced out. */
    boolean isChildTaskLeaseCurrent(TaskLeaseToken taskLease, LeaseToken parentLease, Instant now);

    /** Extends a child lease only while both its task and parent fences are still current. */
    boolean renewChildTaskLease(LeaseToken parentLease, TaskLeaseToken taskLease,
                                Instant now, Duration leaseDuration);

    /** Saves one child-task checkpoint independently, without rewriting the parent Run checkpoint. */
    boolean saveChildTaskFenced(LeaseToken parentLease, TaskLeaseToken taskLease,
                                ChildTaskRecord task, Instant now);

    /** Atomically reserves a set of stable step IDs against the Run's shared model/tool budget. */
    boolean reserveBudget(LeaseToken parentLease, TaskLeaseToken taskLease, BudgetKind kind,
                          Collection<String> reservationIds, int limit, Instant now);
}
