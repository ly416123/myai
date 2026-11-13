package com.example.agent.workflow;

import com.example.agent.collaboration.ChildTaskRecord;
import com.example.agent.collaboration.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;

/** 本地 JSON 检查点存储；每个 run 独立文件并以原子替换写入。 */
public final class JsonFileRunStore implements RunStore {
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");
    private final Path directory;
    private final ObjectMapper mapper;
    private final Map<String, TaskLeaseState> taskLeases = new HashMap<>();

    public JsonFileRunStore(Path directory, ObjectMapper mapper) {
        this.directory = directory.toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    @Override public synchronized void save(RunRecord run) {
        Path target = pathFor(run.runId());
        Path temp = directory.resolve("." + run.runId() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(directory);
            setPermissions(directory, DIRECTORY_PERMISSIONS);
            byte[] data = mapper.writeValueAsBytes(run);
            Files.write(temp, data);
            setPermissions(temp, FILE_PERMISSIONS);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            setPermissions(target, FILE_PERMISSIONS);
        } catch (IOException e) {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
            throw new IllegalStateException("Unable to persist run checkpoint", e);
        }
    }

    @Override public synchronized Optional<RunRecord> find(String runId) {
        Path path = pathFor(runId);
        if (!Files.isRegularFile(path)) return Optional.empty();
        try { return Optional.of(mapper.readValue(path.toFile(), RunRecord.class)); }
        catch (IOException e) { throw new IllegalStateException("Unable to read run checkpoint", e); }
    }

    @Override public synchronized List<RunRecord> list() {
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> readFile(path))
                    .sorted(Comparator.comparing(RunRecord::createdAt)).toList();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to list run checkpoints", e);
        }
    }

    @Override public synchronized List<RunRecord> listRecent(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid Run list limit");
        return list().stream().sorted(Comparator.comparing(RunRecord::createdAt)
                .thenComparing(RunRecord::runId).reversed()).limit(limit).toList();
    }

    @Override public synchronized List<RunRecord> findRecoverable(Instant now, int limit) {
        if (now == null || limit < 1) throw new IllegalArgumentException("Invalid recovery batch request");
        return list().stream().filter(run -> (run.status() == RunStatus.CREATED || run.status() == RunStatus.RUNNING)
                        && (run.leaseExpiresAt() == null || !run.leaseExpiresAt().isAfter(now)))
                .sorted(Comparator.comparing(RunRecord::createdAt).thenComparing(RunRecord::runId))
                .limit(limit).toList();
    }

    @Override public synchronized List<RunRecord> findActiveCollaborativeRuns(Instant now, int limit) {
        if (now == null || limit < 1 || limit > 10_000)
            throw new IllegalArgumentException("Invalid child task scan request");
        return list().stream().filter(run -> run.runType() == RunType.COLLABORATIVE
                        && run.status() == RunStatus.RUNNING && run.leaseExpiresAt() != null
                        && run.leaseExpiresAt().isAfter(now) && !run.cancellationRequested()
                        && run.deadlineAt().isAfter(now) && run.childTasks().stream().anyMatch(task ->
                        task.status() == TaskStatus.PENDING || task.status() == TaskStatus.IN_PROGRESS))
                .sorted(Comparator.comparing(RunRecord::createdAt).thenComparing(RunRecord::runId))
                .limit(limit).toList();
    }

    @Override public synchronized RunQueueSnapshot queueSnapshot(Instant now) {
        if (now == null) throw new IllegalArgumentException("Snapshot time is required");
        List<RunRecord> recoverable = list().stream().filter(run -> (run.status() == RunStatus.CREATED
                        || run.status() == RunStatus.RUNNING)
                && (run.leaseExpiresAt() == null || !run.leaseExpiresAt().isAfter(now))).toList();
        long expiredLeases = recoverable.stream().filter(run -> run.leaseExpiresAt() != null
                && !run.leaseExpiresAt().isAfter(now)).count();
        Instant recentFailureCutoff = now.minus(Duration.ofMinutes(5));
        long recentFailedRuns = list().stream().filter(run -> run.status() == RunStatus.FAILED
                && !run.updatedAt().isBefore(recentFailureCutoff) && !run.updatedAt().isAfter(now)).count();
        Instant oldest = recoverable.stream().map(RunRecord::createdAt).min(Instant::compareTo).orElse(null);
        Duration oldestWait = oldest == null ? Duration.ZERO : Duration.between(oldest, now);
        return new RunQueueSnapshot(recoverable.size(), expiredLeases, recentFailedRuns,
                oldestWait.isNegative() ? Duration.ZERO : oldestWait);
    }

    @Override public synchronized int purgeTerminalRunsBefore(Instant cutoff, int limit) {
        if (cutoff == null || limit < 1 || limit > 10_000)
            throw new IllegalArgumentException("Invalid retention batch request");
        List<String> expired = list().stream()
                .filter(run -> terminal(run.status()) && run.updatedAt().isBefore(cutoff))
                .sorted(Comparator.comparing(RunRecord::updatedAt).thenComparing(RunRecord::runId))
                .limit(limit).map(RunRecord::runId).toList();
        for (String runId : expired) {
            try {
                Files.deleteIfExists(pathFor(runId));
                taskLeases.keySet().removeIf(key -> key.startsWith(runId + '\u0000'));
            } catch (IOException e) {
                throw new IllegalStateException("Unable to delete expired run checkpoint");
            }
        }
        return expired.size();
    }

    @Override public synchronized Optional<LeaseToken> claim(String runId, String ownerId, Instant now, Duration leaseDuration) {
        Optional<RunRecord> found = find(runId);
        if (found.isEmpty()) return Optional.empty();
        RunRecord current = found.get();
        if (terminal(current.status()) || current.leaseExpiresAt() != null && current.leaseExpiresAt().isAfter(now)) {
            return Optional.empty();
        }
        long nextToken = current.fencingToken() + 1;
        current.setLeaseOwner(ownerId);
        current.setLeaseExpiresAt(now.plus(leaseDuration));
        current.setFencingToken(nextToken);
        save(current);
        return Optional.of(new LeaseToken(runId, ownerId, nextToken));
    }

    @Override public synchronized boolean saveFenced(RunRecord run, LeaseToken token, Instant now) {
        Optional<RunRecord> found = find(token.runId());
        if (found.isEmpty()) return false;
        RunRecord current = found.get();
        if (!ownsValidLease(current, token, now) || current.version() != run.version()) return false;
        RunRecord updated = mapper.convertValue(run, RunRecord.class);
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
        save(updated);
        return true;
    }

    @Override public synchronized boolean renew(LeaseToken token, Instant now, Duration leaseDuration) {
        Optional<RunRecord> found = find(token.runId());
        if (found.isEmpty() || !ownsValidLease(found.get(), token, now)) return false;
        RunRecord current = found.get();
        current.setLeaseExpiresAt(now.plus(leaseDuration));
        save(current);
        return true;
    }

    @Override public synchronized boolean requestCancellation(String runId, Instant requestedAt) {
        Optional<RunRecord> found = find(runId);
        if (found.isEmpty() || terminal(found.get().status())) return false;
        RunRecord current = found.get();
        current.setCancellationRequested(true);
        current.touch(requestedAt);
        save(current);
        return true;
    }

    @Override public synchronized Optional<TaskLeaseToken> claimChildTask(LeaseToken parentLease, String taskId,
                                                                           String ownerId, Instant now,
                                                                           Duration leaseDuration) {
        if (taskId == null || taskId.isBlank() || taskId.length() > 80 || ownerId == null || ownerId.isBlank()
                || ownerId.length() > 120 || leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("Invalid child task lease request");
        }
        Optional<RunRecord> found = find(parentLease.runId());
        if (found.isEmpty()) return Optional.empty();
        RunRecord run = found.get();
        if (!ownsValidLease(run, parentLease, now) || run.cancellationRequested()
                || !run.deadlineAt().isAfter(now) || run.childTasks().stream().noneMatch(task ->
                task.taskId().equals(taskId) && (task.status() == com.example.agent.collaboration.TaskStatus.PENDING
                        || task.status() == com.example.agent.collaboration.TaskStatus.IN_PROGRESS))) {
            return Optional.empty();
        }
        String key = parentLease.runId() + '\u0000' + taskId;
        TaskLeaseState current = taskLeases.get(key);
        if (current != null && current.expiresAt().isAfter(now)
                && current.parentFencingToken() == run.fencingToken()) return Optional.empty();
        long fencingToken = current == null ? 1 : current.fencingToken() + 1;
        taskLeases.put(key, new TaskLeaseState(ownerId, now.plus(leaseDuration), fencingToken,
                run.fencingToken()));
        return Optional.of(new TaskLeaseToken(parentLease.runId(), taskId, ownerId, fencingToken));
    }

    @Override public synchronized boolean isChildTaskLeaseCurrent(TaskLeaseToken taskLease,
                                                                    LeaseToken parentLease, Instant now) {
        if (!taskLease.runId().equals(parentLease.runId())) return false;
        TaskLeaseState current = taskLeases.get(parentLease.runId() + '\u0000' + taskLease.taskId());
        Optional<RunRecord> parent = find(parentLease.runId());
        return current != null && taskLease.ownerId().equals(current.ownerId())
                && current.fencingToken() == taskLease.fencingToken() && current.expiresAt().isAfter(now)
                && current.parentFencingToken() == parentLease.fencingToken()
                && parent.isPresent() && ownsValidLease(parent.get(), parentLease, now);
    }

    @Override public synchronized boolean renewChildTaskLease(LeaseToken parentLease, TaskLeaseToken taskLease,
                                                               Instant now, Duration leaseDuration) {
        if (parentLease == null || taskLease == null || !parentLease.runId().equals(taskLease.runId())
                || now == null || leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative())
            return false;
        if (!isChildTaskLeaseCurrent(taskLease, parentLease, now)) return false;
        String key = parentLease.runId() + '\u0000' + taskLease.taskId();
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
        Optional<RunRecord> found = find(parentLease.runId());
        if (found.isEmpty()) return false;
        RunRecord parent = found.get();
        boolean supervisorSettlement = taskLease == null && terminal(parent.status()) && terminal(task.status())
                && ownsCurrentLease(parent, parentLease, now);
        if (!ownsValidLease(parent, parentLease, now) && !supervisorSettlement) return false;
        if (taskLease != null) {
            if (!isChildTaskLeaseCurrent(taskLease, parentLease, now)) return false;
        } else {
            TaskLeaseState activeLease = taskLeases.get(parentLease.runId() + '\u0000' + task.taskId());
            if (activeLease != null && activeLease.expiresAt().isAfter(now)
                    && activeLease.parentFencingToken() == parentLease.fencingToken() && !supervisorSettlement) return false;
            ChildTaskRecord existing = parent.childTasks().stream().filter(value -> value.taskId().equals(task.taskId()))
                    .findFirst().orElse(null);
            if (existing == null || existing.status() != TaskStatus.PENDING && existing.status() != TaskStatus.IN_PROGRESS)
                return false;
        }
        parent.replaceChildTask(task.taskId(), task);
        save(parent);
        if (terminal(task.status())) taskLeases.remove(parentLease.runId() + '\u0000' + task.taskId());
        return true;
    }

    @Override public synchronized boolean reserveBudget(LeaseToken parentLease, TaskLeaseToken taskLease,
                                                          BudgetKind kind, Collection<String> reservationIds,
                                                          int limit, Instant now) {
        if (parentLease == null || kind == null || reservationIds == null || reservationIds.isEmpty()
                || reservationIds.size() > 1000 || limit < 0 || now == null
                || reservationIds.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 80))
            throw new IllegalArgumentException("Invalid Run budget reservation");
        Optional<RunRecord> found = find(parentLease.runId());
        if (found.isEmpty()) return false;
        RunRecord run = found.get();
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
        if (kind == BudgetKind.MODEL_CALL) run.setModelCalls(current + newReservations);
        else run.setToolCalls(current + newReservations);
        var allReservations = new java.util.LinkedHashMap<>(run.budgetReservations());
        ids.forEach(id -> allReservations.putIfAbsent(id, kind));
        run.setBudgetReservations(allReservations);
        save(run);
        return true;
    }

    private static boolean ownsValidLease(RunRecord current, LeaseToken token, Instant now) {
        return current != null && !terminal(current.status()) && ownsCurrentLease(current, token, now);
    }

    private static boolean ownsCurrentLease(RunRecord current, LeaseToken token, Instant now) {
        return current != null && token.ownerId().equals(current.leaseOwner())
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
    private record TaskLeaseState(String ownerId, Instant expiresAt, long fencingToken,
                                  long parentFencingToken) { }

    private RunRecord readFile(Path path) {
        try { return mapper.readValue(path.toFile(), RunRecord.class); }
        catch (IOException e) { throw new IllegalStateException("Unable to read run checkpoint", e); }
    }

    private Path pathFor(String runId) {
        if (runId == null || !runId.matches("[A-Za-z0-9_-]{1,80}")) {
            throw new IllegalArgumentException("Invalid run ID");
        }
        Path path = directory.resolve(runId + ".json").normalize();
        if (!path.getParent().equals(directory)) throw new IllegalArgumentException("Invalid run ID");
        return path;
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) {
        try { Files.setPosixFilePermissions(path, permissions); }
        catch (UnsupportedOperationException | IOException ignored) { /* 非 POSIX 文件系统使用系统默认权限。 */ }
    }
}
