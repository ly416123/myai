package com.example.agent.workflow;

import com.example.agent.collaboration.PlannedTask;
import com.example.agent.collaboration.TaskPlan;
import com.example.agent.collaboration.TaskPlanValidator;
import com.example.agent.collaboration.WorkerRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TaskPlanValidatorTest {
    private final WorkerRegistry workers = new WorkerRegistry();
    private final TaskPlanValidator validator = new TaskPlanValidator(workers, 10);

    TaskPlanValidatorTest() {
        workers.register("math", "Perform deterministic calculations", "Only calculate requested values", Set.of());
        workers.register("writer", "Explain results", "Use only supplied context", Set.of());
    }

    @Test
    void parsesAndAcceptsAValidPlanWithExplicitDeliverablesAndDependencies() throws Exception {
        TaskPlan plan = new ObjectMapper().readValue("""
                {"route":"DELEGATE","tasks":[
                  {"taskId":"t1","workerId":"math","description":"compute 6 times 7","dependencies":[],"deliverableIds":["number"]},
                  {"taskId":"t2","workerId":"writer","description":"explain the result","dependencies":["t1"],"deliverableIds":["explanation"]}],
                  "deliverables":[{"deliverableId":"number","description":"The computed number"},{"deliverableId":"explanation","description":"A brief explanation"}]}
                """, TaskPlan.class);

        assertDoesNotThrow(() -> validator.validate(plan));
    }

    @Test
    void rejectsDuplicateTaskIdsAndNormalizedDuplicateTasksBeforeExecution() {
        TaskPlan duplicateId = delegate(
                task("t1", "math", "add 1 and 2", List.of(), List.of("d1")),
                task("t1", "math", "add 2 and 3", List.of(), List.of("d2")));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(duplicateId));

        TaskPlan normalizedDuplicate = delegate(
                task("t1", "math", "  Add   one  ", List.of(), List.of("d1")),
                task("t2", "math", "Add one", List.of(), List.of("d2")));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(normalizedDuplicate));

        TaskPlan unicodeNfcDuplicate = delegate(
                task("t3", "math", "café", List.of(), List.of("d1")),
                task("t4", "math", "cafe\u0301", List.of(), List.of("d2")));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(unicodeNfcDuplicate));

        TaskPlan caseSensitiveDescriptions = delegate(
                task("t5", "math", "API Request", List.of(), List.of("d1")),
                task("t6", "math", "api request", List.of(), List.of("d2")));
        assertDoesNotThrow(() -> validator.validate(caseSensitiveDescriptions));
    }

    @Test
    void rejectsUnknownWorkersInvalidDependenciesCyclesAndUncoveredDeliverables() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(delegate(
                task("t1", "missing", "task", List.of(), List.of("d1")))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(delegate(
                task("t1", "math", "task", List.of("missing"), List.of("d1")))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(delegate(
                task("t1", "math", "first", List.of("t2"), List.of("d1")),
                task("t2", "writer", "second", List.of("t1"), List.of("d2")))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(new TaskPlan(
                TaskPlan.Route.DELEGATE,
                List.of(task("t1", "math", "task", List.of(), List.of())),
                List.of(new TaskPlan.Deliverable("d1", "missing coverage")))));
    }

    @Test
    void dependencySetsAreComparedAsStructuredValuesEvenWhenIdsContainSeparators() {
        List<PlannedTask> tasks = List.of(
                task("a\u0001b", "math", "first prerequisite", List.of(), List.of("p1")),
                task("c", "writer", "second prerequisite", List.of(), List.of("p2")),
                task("a", "math", "third prerequisite", List.of(), List.of("p3")),
                task("b\u0001c", "writer", "fourth prerequisite", List.of(), List.of("p4")),
                task("target-one", "math", "combine inputs", List.of("a\u0001b", "c"), List.of("r1")),
                task("target-two", "math", "combine inputs", List.of("a", "b\u0001c"), List.of("r2")));
        TaskPlan plan = delegate(tasks.toArray(PlannedTask[]::new));

        assertDoesNotThrow(() -> validator.validate(plan));
    }

    @Test
    void rejectsTaskPlansOverTheConfiguredTaskLimit() {
        PlannedTask[] tasks = new PlannedTask[11];
        for (int i = 0; i < tasks.length; i++) {
            tasks[i] = task("task-" + i, i % 2 == 0 ? "math" : "writer", "distinct task " + i,
                    List.of(), List.of("d-" + i));
        }
        assertThrows(IllegalArgumentException.class, () -> validator.validate(delegate(tasks)));
    }

    @Test
    void noSplitMustNotContainUnvalidatedTasks() {
        assertDoesNotThrow(() -> validator.validate(new TaskPlan(TaskPlan.Route.NO_SPLIT, List.of(), List.of())));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(new TaskPlan(
                TaskPlan.Route.NO_SPLIT,
                List.of(task("t1", "math", "hidden task", List.of(), List.of())), List.of())));
    }

    private static TaskPlan delegate(PlannedTask... tasks) {
        List<PlannedTask> planTasks = List.of(tasks);
        List<TaskPlan.Deliverable> deliverables = planTasks.stream()
                .flatMap(task -> task.deliverableIds().stream()).distinct()
                .map(id -> new TaskPlan.Deliverable(id, "Deliverable " + id)).toList();
        return new TaskPlan(TaskPlan.Route.DELEGATE, planTasks, deliverables);
    }

    private static PlannedTask task(String id, String worker, String description,
                                     List<String> dependencies, List<String> deliverables) {
        return new PlannedTask(id, worker, description, dependencies, deliverables);
    }
}
