package com.example.agent.collaboration;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 在任何 Worker 启动前验证计划结构、静态权限、交付映射和依赖无环性。 */
public final class TaskPlanValidator {
    private final WorkerRegistry workers;
    private final int maxTasks;

    public TaskPlanValidator(WorkerRegistry workers, int maxTasks) {
        if (maxTasks < 1) throw new IllegalArgumentException("maxTasks must be positive");
        this.workers = Objects.requireNonNull(workers);
        this.maxTasks = maxTasks;
    }

    public void validate(TaskPlan plan) {
        if (plan == null || plan.route() == null) throw invalid();
        if (plan.route() == TaskPlan.Route.NO_SPLIT) {
            if (!plan.tasks().isEmpty() || !plan.deliverables().isEmpty()) throw invalid();
            return;
        }
        List<PlannedTask> tasks = plan.tasks();
        if (tasks.isEmpty() || tasks.size() > maxTasks || plan.deliverables().isEmpty()) throw invalid();
        Set<String> taskIds = new HashSet<>();
        Set<DuplicateKey> duplicateKeys = new HashSet<>();
        Map<String, PlannedTask> byId = new HashMap<>();
        for (PlannedTask task : tasks) {
            if (task == null || blank(task.taskId()) || blank(task.workerId()) || blank(task.description())) throw invalid();
            if (!taskIds.add(task.taskId())) throw invalid();
            WorkerSpec worker = workers.find(task.workerId()).orElseThrow(TaskPlanValidator::invalid);
            DuplicateKey key = new DuplicateKey(worker.workerId(), normalize(task.description()),
                    task.dependencies().stream().sorted().toList());
            if (!duplicateKeys.add(key)) throw invalid();
            if (new HashSet<>(task.dependencies()).size() != task.dependencies().size()
                    || new HashSet<>(task.deliverableIds()).size() != task.deliverableIds().size()) throw invalid();
            byId.put(task.taskId(), task);
        }
        Set<String> deliverableIds = new HashSet<>();
        for (TaskPlan.Deliverable deliverable : plan.deliverables()) {
            if (deliverable == null || blank(deliverable.deliverableId()) || blank(deliverable.description())
                    || !deliverableIds.add(deliverable.deliverableId())) throw invalid();
        }
        Set<String> mapped = new HashSet<>();
        for (PlannedTask task : tasks) {
            for (String dependency : task.dependencies()) {
                if (!byId.containsKey(dependency) || dependency.equals(task.taskId())) throw invalid();
            }
            for (String deliverableId : task.deliverableIds()) {
                if (!deliverableIds.contains(deliverableId)) throw invalid();
                mapped.add(deliverableId);
            }
        }
        if (!mapped.equals(deliverableIds)) throw invalid();
        assertAcyclic(tasks, byId);
    }

    private static void assertAcyclic(List<PlannedTask> tasks, Map<String, PlannedTask> byId) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (PlannedTask task : tasks) visit(task, byId, visiting, visited);
    }

    private static void visit(PlannedTask task, Map<String, PlannedTask> byId,
                              Set<String> visiting, Set<String> visited) {
        if (visited.contains(task.taskId())) return;
        if (!visiting.add(task.taskId())) throw invalid();
        for (String id : task.dependencies()) visit(byId.get(id), byId, visiting, visited);
        visiting.remove(task.taskId());
        visited.add(task.taskId());
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).strip().replaceAll("(?U)\\s+", " ");
    }

    /** 使用结构化字段避免分隔符出现在合法 ID/描述中时产生误碰撞。 */
    private record DuplicateKey(String workerId, String description, List<String> dependencies) { }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid task plan"); }
}
