package com.example.agent.collaboration;

import com.fasterxml.jackson.databind.JsonNode;

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
            validateWorkerBindings(task, worker);
            DuplicateKey key = new DuplicateKey(worker.workerId(), normalize(task.description()),
                    task.dependencies().stream().sorted().toList());
            if (!duplicateKeys.add(key)) throw invalid();
            if (new HashSet<>(task.dependencies()).size() != task.dependencies().size()
                    || new HashSet<>(task.deliverableIds()).size() != task.deliverableIds().size()) throw invalid();
            if (task.workerVersion() != null) throw invalid(); // version is assigned from the registry after validation
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
            WorkerSpec targetWorker = workers.find(task.workerId()).orElseThrow(TaskPlanValidator::invalid);
            if (targetWorker.version() != null) {
                for (InputBinding binding : task.inputBindings()) {
                    if (binding.source() == InputBinding.Source.DEPENDENCY_OUTPUT) {
                        validateOutputPointer(binding, byId);
                    }
                }
            }
            for (String deliverableId : task.deliverableIds()) {
                if (!deliverableIds.contains(deliverableId)) throw invalid();
                mapped.add(deliverableId);
            }
        }
        if (!mapped.equals(deliverableIds)) throw invalid();
        assertAcyclic(tasks, byId);
    }

    private static void validateWorkerBindings(PlannedTask task, WorkerSpec worker) {
        if (worker.version() == null) return; // transitional compatibility for pre-contract test fixtures
        if (task.acceptanceCriteria().isEmpty() || task.acceptanceCriteria().stream().anyMatch(TaskPlanValidator::blank))
            throw contractIssue("*", "PLAN", "MISSING_ACCEPTANCE_CRITERIA");
        var properties = worker.inputSchema().path("properties");
        if (!properties.isObject()) throw invalid();
        Set<String> targets = new HashSet<>();
        Set<String> dependencies = Set.copyOf(task.dependencies());
        for (InputBinding binding : task.inputBindings()) {
            if (binding == null || blank(binding.field()) || !properties.has(binding.field())
                    || !targets.add(binding.field()) || binding.source() == null) {
                throw contractIssue(binding == null ? "*" : String.valueOf(binding.field()), "PLAN",
                        "INVALID_OR_DUPLICATE_INPUT_FIELD");
            }
            if (binding.source() == InputBinding.Source.PARENT_REQUEST) {
                if (binding.parentValue() == null || binding.parentValue().isNull())
                    throw contractIssue(binding.field(), "PARENT_REQUEST", "MISSING_PARENT_VALUE");
                if (binding.dependencyTaskId() != null || binding.jsonPointer() != null)
                    throw contractIssue(binding.field(), "PARENT_REQUEST", "INVALID_BINDING_SHAPE");
            } else if (binding.source() == InputBinding.Source.DEPENDENCY_OUTPUT) {
                if (binding.parentValue() != null || blank(binding.dependencyTaskId())
                        || !dependencies.contains(binding.dependencyTaskId()) || binding.jsonPointer() == null
                        || !(binding.jsonPointer().isEmpty() || binding.jsonPointer().startsWith("/")))
                    throw contractIssue(binding.field(), "DEPENDENCY_OUTPUT", "INVALID_DEPENDENCY_BINDING");
            }
        }
        Set<String> required = new HashSet<>();
        worker.inputSchema().path("required").forEach(node -> required.add(node.asText()));
        if (!targets.containsAll(required)) {
            List<WorkerContractIssue> issues = required.stream().filter(field -> !targets.contains(field))
                    .map(field -> new WorkerContractIssue(field, "UNBOUND", "MISSING_REQUIRED_INPUT_BINDING"))
                    .toList();
            throw new WorkerContractException(issues);
        }
    }

    private void validateOutputPointer(InputBinding binding, Map<String, PlannedTask> byId) {
        PlannedTask sourceTask = byId.get(binding.dependencyTaskId());
        if (sourceTask == null) throw contractIssue(binding.field(), "DEPENDENCY_OUTPUT", "DEPENDENCY_NOT_FOUND");
        WorkerSpec sourceWorker = workers.find(sourceTask.workerId()).orElseThrow(TaskPlanValidator::invalid);
        if (sourceWorker.version() == null) {
            throw contractIssue(binding.field(), "DEPENDENCY_OUTPUT", "DEPENDENCY_HAS_NO_OUTPUT_CONTRACT");
        }
        JsonNode schema = sourceWorker.outputSchema();
        String pointer = binding.jsonPointer();
        if (pointer.isEmpty()) return;
        for (String rawToken : pointer.substring(1).split("/", -1)) {
            String token = rawToken.replace("~1", "/").replace("~0", "~");
            String type = schema.path("type").asText();
            if ("object".equals(type)) {
                schema = schema.path("properties").path(token);
                if (schema.isMissingNode())
                    throw contractIssue(binding.field(), "DEPENDENCY_OUTPUT", "JSON_POINTER_NOT_DECLARED");
            } else if ("array".equals(type) && token.matches("0|[1-9][0-9]*")) {
                schema = schema.path("items");
                if (schema.isMissingNode())
                    throw contractIssue(binding.field(), "DEPENDENCY_OUTPUT", "JSON_POINTER_NOT_DECLARED");
            } else {
                throw contractIssue(binding.field(), "DEPENDENCY_OUTPUT", "JSON_POINTER_NOT_DECLARED");
            }
        }
    }

    private static WorkerContractException contractIssue(String field, String source, String reason) {
        return new WorkerContractException(List.of(new WorkerContractIssue(field, source, reason)));
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
