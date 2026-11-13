package com.example.agent.collaboration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 只允许应用启动时静态注册的 Worker。 */
public final class WorkerRegistry {
    private final Map<String, WorkerSpec> workers = new LinkedHashMap<>();

    public synchronized void register(WorkerSpec worker) {
        if (workers.putIfAbsent(worker.workerId(), worker) != null) {
            throw new IllegalArgumentException("Worker already registered");
        }
    }

    public synchronized void register(String id, String responsibility, String prompt, Set<String> tools) {
        register(new WorkerSpec(id, responsibility, prompt, tools.stream().sorted().toList(), 3, 8));
    }

    public synchronized Optional<WorkerSpec> find(String workerId) {
        return Optional.ofNullable(workers.get(workerId));
    }

    public synchronized List<WorkerSpec> all() { return List.copyOf(workers.values()); }
}
