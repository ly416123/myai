package com.example.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.agent.collaboration.ChildTaskRecord;
import com.example.agent.collaboration.TaskStatus;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** 用于单元测试和本地验证的内存 RunStore。保存时复制记录，模拟存储边界。 */
public final class InMemoryRunStore implements RunStore {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final ConcurrentHashMap<String, RunRecord> records = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TaskLeaseState> taskLeases = new ConcurrentHashMap<>();

    @Override public synchronized void save(RunRecord run) { records.put(run.runId(), copy(run)); }
    @Override public Optional<RunRecord> find(String runId) { return Optional.ofNullable(records.get(runId)).map(InMemoryRunStore::copy); }
    @Override public List<RunRecord> list() {
        return records.values().stream().map(InMemoryRunStore::copy)
                .sorted(Comparator.comparing(RunRecord::createdAt)).toList();
    }
    @Override public List<RunRecord> listRecent(int limit) {
        validateRunListLimit(limit);
        return records.values().stream().sorted(Comparator.comparing(RunRecord::createdAt)
                        .thenComparing(RunRecord::runId).reversed()).limit(limit)
                .map(InMemoryRunStore::copy).toList();
    }
    @Override public List<RunRecord> findRecoverable(Instant now, int limit) {
        if (now == null || limit < 1) throw new IllegalArgumentException("Invalid recovery batch request");
        return records.values().stream().filter(run -> recoverable(run, now))
                .sorted(Comparator.comparing(RunRecord::createdAt).thenComparing(RunRecord::runId)).limit(limit)
                .map(InMemoryRunStore::copy).toList();
    }
    @Override public List<RunRecord> findActiveCollaborativeRuns(Instant now, int limit) {
        if (now == null || limit < 1 || limit > 10_000)
            throw new IllegalArgumentException("Invalid child task scan request");
        return records.values().stream().filter(run -> run.runType() == RunType.COLLABORATIVE
                        && run.status() == RunStatus.RUNNING && run.leaseExpiresAt() != null
                        && run.leaseExpiresAt().isAfter(now) && !run.cancellationRequested()
                        && run.deadlineAt().isAfter(now) && run.childTasks().stream().anyMatch(task ->
                        task.status() == TaskStatus.PENDING || task.status() == TaskStatus.IN_PROGRESS))
                .sorted(Comparator.comparing(RunRecord::createdAt).thenComparing(RunRecord::runId))
                .limit(limit).map(InMemoryRunStore::copy).toList();
    }
    @Override public RunQueueSnapshot queueSnapshot(Instant now) {
        if (now == null) throw new IllegalArgumentException("Snapshot time is required");
        List<RunRecord> recoverableRuns = records.values().stream().filter(run -> recoverable(run, now)).toList();
        long expiredLeases = recoverableRuns.stream().filter(run -> run.leaseExpiresAt() != null
                && !run.leaseExpiresAt().isAfter(now)).count();
        Instant recentFailureCutoff = now.minus(Duration.ofMinutes(5));
        long recentFailedRuns = list().stream().filter(run -> run.status() == RunStatus.FAILED
                && !run.updatedAt().isBefore(recentFailureCutoff) && !run.updatedAt().isAfter(now)).count();
        Instant oldest = recoverableRuns.stream().map(RunRecord::createdAt).min(Instant::compareTo).orElse(null);
        Duration oldestWait = oldest == null ? Duration.ZERO : Duration.between(oldest, now);
        return new RunQueueSnapshot(recoverableRuns.size(), expiredLeases, recentFailedRuns, oldestWait.isNegative()
                ? Duration.ZERO : oldestWait);
    }

    @Override public synchronized int purgeTerminalRunsBefore(Instant cutoff, int limit) {
        if (cutoff == null || limit < 1 || limit > 10_000)
            throw new IllegalArgumentException("Invalid retention batch request");
        List<String> expired = records.values().stream()
                .filter(run -> terminal(run.status()) && run.updatedAt().isBefore(cutoff))
                .sorted(Comparator.comparing(RunRecord::updatedAt).thenComparing(RunRecord::runId))
                .limit(limit).map(RunRecord::runId).toList();
        for (String runId : expired) {
            records.remove(runId);
            taskLeases.keySet().removeIf(key -> key.startsWith(runId + '\u0000'));
        }
        return expired.size();
    }

    @Override public synchronized Optional<LeaseToken> claim(String runId, String ownerId, Instant now, Duration leaseDuration) {
        RunRecord current = records.get(runId);
        if (current == null || terminal(current.status())) return Optional.empty();
        if (current.leaseExpiresAt() != null && current.leaseExpiresAt().isAfter(now)) return Optional.empty();
        RunRecord claimed = copy(current);
        long fencingToken = current.fencingToken() + 1;
        claimed.setLeaseOwner(ownerId);
        claimed.setLeaseExpiresAt(now.plus(leaseDuration));
        claimed.setFencingToken(fencingToken);
        records.put(runId, claimed);
        return Optional.of(new LeaseToken(runId, ownerId, fencingToken));
    }

    @Override public synchronized boolean saveFenced(RunRecord run, LeaseToken token, Instant now) {
        RunRecord current = records.get(token.runId());
        if (!ownsValidLease(current, token, now) || current.version() != run.version()) return false;
        RunRecord updated = copy(run);
        updated.setChildTasks(mergeChildTasks(current.childTasks(), run.childTasks()));
        updated.setLeaseOwner(current.leaseOwner());
        updated.setLeaseExpiresAt(current.leaseExpiresAt());
        updated.setFencingToken(current.fencingToken());
        updated.setVersion(current.version() + 1);
        updated.setCancellationRequested(current.cancellationRequested() || run.cancellationRequested());
        updated.setModelCalls(Math.max(current.modelCalls(), run.modelCalls()));
        updated.setToolCalls(Math.max(current.toolCalls(), run.toolCalls()));
        var reservations = new java.util.LinkedHashMap<>(current.budgetReservations());
        reservations.putAll(run.budgetReservations());
        updated.setBudgetReservations(reservations);
        run.setVersion(current.version() + 1);
        run.setCancellationRequested(updated.cancellationRequested());
        run.setModelCalls(updated.modelCalls());
        run.setToolCalls(updated.toolCalls());
        run.setBudgetReservations(reservations);
        records.put(run.runId(), updated);
        return true;
    }

    @Override public synchronized boolean renew(LeaseToken token, Instant now, Duration leaseDuration) {
        RunRecord current = records.get(token.runId());
        if (!ownsValidLease(current, token, now)) return false;
        current.setLeaseExpiresAt(now.plus(leaseDuration));
        records.put(token.runId(), copy(current));
        return true;
    }

    @Override public synchronized boolean requestCancellation(String runId, Instant requestedAt) {
        RunRecord current = records.get(runId);
        if (current == null || terminal(current.status())) return false;
        current.setCancellationRequested(true);
        current.touch(requestedAt);
        records.put(runId, copy(current));
        return true;
    }

    @Override public synchronized Optional<TaskLeaseToken> claimChildTask(LeaseToken parentLease, String taskId,
                                                                           String ownerId, Instant now,
                                                                           Duration leaseDuration) {
        validateTaskLease(taskId, ownerId, leaseDuration);
        RunRecord parent = records.get(parentLease.runId());
        if (!ownsValidLease(parent, parentLease, now) || parent.cancellationRequested()
                || !parent.deadlineAt().isAfter(now) || parent.childTasks().stream().noneMatch(task ->
                task.taskId().equals(taskId) && (task.status() == TaskStatus.PENDING
                        || task.status() == TaskStatus.IN_PROGRESS))) return Optional.empty();
        String key = taskLeaseKey(parentLease.runId(), taskId);
        TaskLeaseState current = taskLeases.get(key);
        if (current != null && current.expiresAt().isAfter(now)
                && current.parentFencingToken() == parent.fencingToken()) return Optional.empty();
        long fencingToken = current == null ? 1 : current.fencingToken() + 1;
        taskLeases.put(key, new TaskLeaseState(ownerId, now.plus(leaseDuration), fencingToken,
                parent.fencingToken()));
        return Optional.of(new TaskLeaseToken(parentLease.runId(), taskId, ownerId, fencingToken));
    }

    @Override public synchronized boolean isChildTaskLeaseCurrent(TaskLeaseToken taskLease,
                                                                    LeaseToken parentLease, Instant now) {
        if (!taskLease.runId().equals(parentLease.runId())) return false;
        TaskLeaseState current = taskLeases.get(taskLeaseKey(taskLease.runId(), taskLease.taskId()));
        RunRecord parent = records.get(parentLease.runId());
        return current != null && taskLease.ownerId().equals(current.ownerId())
                && current.fencingToken() == taskLease.fencingToken()
                && current.expiresAt().isAfter(now) && current.parentFencingToken() == parentLease.fencingToken()
                && ownsValidLease(parent, parentLease, now);
    }

    @Override public synchronized boolean renewChildTaskLease(LeaseToken parentLease, TaskLeaseToken taskLease,
                                                               Instant now, Duration leaseDuration) {
        if (!validChildLeaseRequest(parentLease, taskLease, now, leaseDuration)) return false;
        if (!isChildTaskLeaseCurrent(taskLease, parentLease, now)) return false;
        String key = taskLeaseKey(taskLease.runId(), taskLease.taskId());
        TaskLeaseState current = taskLeases.get(key);
        taskLeases.put(key, new TaskLeaseState(current.ownerId(), now.plus(leaseDuration),
                current.fencingToken(), current.parentFencingToken()));
        return true;
    }

    @Override public synchronized boolean saveChildTaskFenced(LeaseToken parentLease, TaskLeaseToken taskLease,
                                                               ChildTaskRecord task, Instant now) {
        if (parentLease == null || task == null || now == null || taskLease != null
                && (!parentLease.runId().equals(taskLease.runId()) || !task.taskId().equals(taskLease.taskId()))) {
            throw new IllegalArgumentException("Invalid child task checkpoint");
        }
        RunRecord parent = records.get(parentLease.runId());
        boolean supervisorSettlement = taskLease == null && parent != null && terminal(parent.status()) && terminal(task.status())
                && ownsCurrentLease(parent, parentLease, now);
        if (!ownsValidLease(parent, parentLease, now) && !supervisorSettlement) return false;
        if (taskLease != null) {
            if (!isChildTaskLeaseCurrent(taskLease, parentLease, now)) return false;
        } else {
            TaskLeaseState lease = taskLeases.get(taskLeaseKey(parentLease.runId(), task.taskId()));
            if (lease != null && lease.expiresAt().isAfter(now)
                    && lease.parentFencingToken() == parentLease.fencingToken() && !supervisorSettlement) return false;
            ChildTaskRecord existing = parent.childTasks().stream().filter(value -> value.taskId().equals(task.taskId()))
                    .findFirst().orElse(null);
            if (existing == null || existing.status() != TaskStatus.PENDING && existing.status() != TaskStatus.IN_PROGRESS)
                return false;
        }
        RunRecord updated = copy(parent);
        updated.replaceChildTask(task.taskId(), task);
        records.put(parentLease.runId(), updated);
        if (terminal(task.status())) taskLeases.remove(taskLeaseKey(parentLease.runId(), task.taskId()));
        return true;
    }

    @Override public synchronized boolean reserveBudget(LeaseToken parentLease, TaskLeaseToken taskLease,
                                                          BudgetKind kind, Collection<String> reservationIds,
                                                          int limit, Instant now) {
        validateBudgetRequest(parentLease, kind, reservationIds, limit, now);
        RunRecord run = records.get(parentLease.runId());
        if (!ownsValidLease(run, parentLease, now) || run.cancellationRequested() || !run.deadlineAt().isAfter(now)
                || taskLease != null && !isChildTaskLeaseCurrent(taskLease, parentLease, now)) return false;
        List<String> ids = List.copyOf(new HashSet<>(reservationIds));
        if (ids.size() != reservationIds.size()) throw new IllegalArgumentException("Duplicate budget reservation ID");
        int newReservations = 0;
        for (String id : ids) {
            BudgetKind existing = run.budgetReservations().get(id);
            if (existing == null) newReservations++;
            else if (existing != kind) return false;
        }
        int current = kind == BudgetKind.MODEL_CALL ? run.modelCalls() : run.toolCalls();
        if (current + newReservations > limit) return false;
        if (newReservations == 0) return true;
        RunRecord updated = copy(run);
        if (kind == BudgetKind.MODEL_CALL) updated.setModelCalls(current + newReservations);
        else updated.setToolCalls(current + newReservations);
        var allReservations = new java.util.LinkedHashMap<>(updated.budgetReservations());
        ids.forEach(id -> allReservations.putIfAbsent(id, kind));
        updated.setBudgetReservations(allReservations);
        records.put(run.runId(), updated);
        return true;
    }

    private static boolean ownsValidLease(RunRecord current, LeaseToken token, Instant now) {
        return current != null && !terminal(current.status()) && ownsCurrentLease(current, token, now);
    }

    private static boolean ownsCurrentLease(RunRecord current, LeaseToken token, Instant now) {
        return current != null
                && token.ownerId().equals(current.leaseOwner())
                && token.fencingToken() == current.fencingToken()
                && current.leaseExpiresAt() != null && current.leaseExpiresAt().isAfter(now);
    }

    private static boolean terminal(RunStatus status) {
        return status == RunStatus.SUCCEEDED || status == RunStatus.PARTIAL
                || status == RunStatus.FAILED || status == RunStatus.CANCELLED;
    }

    private static boolean terminal(TaskStatus status) {
        return status == TaskStatus.SUCCEEDED || status == TaskStatus.FAILED
                || status == TaskStatus.SKIPPED || status == TaskStatus.INDETERMINATE;
    }
    private static List<ChildTaskRecord> mergeChildTasks(List<ChildTaskRecord> current,
                                                          List<ChildTaskRecord> incoming) {
        var merged = new java.util.LinkedHashMap<String, ChildTaskRecord>();
        current.forEach(task -> merged.put(task.taskId(), task));
        incoming.forEach(task -> merged.putIfAbsent(task.taskId(), task));
        return List.copyOf(merged.values());
    }
    private static boolean recoverable(RunRecord run, Instant now) {
        return !terminal(run.status()) && (run.status() == RunStatus.CREATED || run.status() == RunStatus.RUNNING)
                && (run.leaseExpiresAt() == null || !run.leaseExpiresAt().isAfter(now));
    }
    private static void validateTaskLease(String taskId, String ownerId, Duration duration) {
        if (taskId == null || taskId.isBlank() || taskId.length() > 80 || ownerId == null || ownerId.isBlank()
                || ownerId.length() > 120 || duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Invalid child task lease request");
        }
    }
    private static boolean validChildLeaseRequest(LeaseToken parentLease, TaskLeaseToken taskLease,
                                                  Instant now, Duration duration) {
        return parentLease != null && taskLease != null && parentLease.runId().equals(taskLease.runId())
                && now != null && duration != null && !duration.isZero() && !duration.isNegative();
    }
    private static void validateRunListLimit(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid Run list limit");
    }
    private static String taskLeaseKey(String runId, String taskId) { return runId + '\u0000' + taskId; }
    private static void validateBudgetRequest(LeaseToken parentLease, BudgetKind kind,
                                              Collection<String> reservationIds, int limit, Instant now) {
        if (parentLease == null || kind == null || reservationIds == null || reservationIds.isEmpty()
                || reservationIds.size() > 1000 || limit < 0 || now == null
                || reservationIds.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 80))
            throw new IllegalArgumentException("Invalid Run budget reservation");
    }
    private record TaskLeaseState(String ownerId, java.time.Instant expiresAt, long fencingToken,
                                  long parentFencingToken) { }
    private static RunRecord copy(RunRecord run) {
        return JSON.convertValue(run, RunRecord.class);
    }
}
