package com.example.agent.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkerContractValidatorTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void rejectsMissingRequiredWorkerInputBeforeExecution() throws Exception {
        WorkerSpec worker = worker();
        var input = json.createObjectNode().put("city", "Beijing");
        WorkerContractValidator validator = new WorkerContractValidator(json);

        assertFalse(validator.isValidInput(worker.inputSchema(), input));
        assertFalse(validator.isValidInput(worker.inputSchema(), json.createObjectNode()
                .put("city", 7).put("date", "tomorrow")));
        assertFalse(validator.isValidOutput(worker.outputSchema(), json.createObjectNode()
                .put("city", "Beijing")));
    }

    @Test
    void validatesRequiredFieldsAndRejectsUnknownOrDuplicateBindingTargets() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        registry.register(worker());
        TaskPlanValidator plans = new TaskPlanValidator(registry, 4);

        assertDoesNotThrow(() -> plans.validate(plan(List.of(parentBinding("city", "Beijing"),
                parentBinding("date", "tomorrow")))));
        WorkerContractException missing = assertThrows(WorkerContractException.class,
                () -> plans.validate(plan(List.of(parentBinding("city", "Beijing")))));
        assertEquals("date", missing.issues().getFirst().field());
        assertEquals("MISSING_REQUIRED_INPUT_BINDING", missing.issues().getFirst().reasonCode());
        assertThrows(IllegalArgumentException.class, () -> plans.validate(plan(List.of(parentBinding("city", "Beijing"),
                parentBinding("city", "Shanghai"), parentBinding("date", "tomorrow")))));
        assertThrows(IllegalArgumentException.class, () -> plans.validate(plan(List.of(parentBinding("extra", "x"),
                parentBinding("city", "Beijing"), parentBinding("date", "tomorrow")))));
    }

    @Test
    void dependencyBindingMustReferenceADirectDependencyAndJsonPointer() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        registry.register(worker());
        TaskPlanValidator plans = new TaskPlanValidator(registry, 4);
        PlannedTask consumer = new PlannedTask("consumer", "weather", "forecast", List.of("source"),
                List.of("result"), List.of(
                new InputBinding("city", InputBinding.Source.DEPENDENCY_OUTPUT, null, "not-direct", "/city"),
                parentBinding("date", "tomorrow")), List.of("return a forecast"), null);

        assertThrows(IllegalArgumentException.class, () -> plans.validate(new TaskPlan(TaskPlan.Route.DELEGATE,
                List.of(new PlannedTask("source", "weather", "source", List.of(), List.of("source-result"),
                                List.of(parentBinding("city", "Beijing"), parentBinding("date", "today")),
                                List.of("return source"), null), consumer),
                List.of(new TaskPlan.Deliverable("source-result", "source"),
                        new TaskPlan.Deliverable("result", "result")))));
    }

    @Test
    void resolvesOnlyTheSelectedDependencyOutputField() throws Exception {
        WorkerSpec worker = worker();
        PlannedTask dependencyPlan = new PlannedTask("source", "weather", "source", List.of(), List.of());
        ChildTaskRecord dependency = ChildTaskRecord.pending(dependencyPlan)
                .finish(TaskStatus.SUCCEEDED, "{\"city\":\"Paris\",\"privateNote\":\"do not forward\"}",
                        null, java.time.Instant.now());
        PlannedTask consumer = new PlannedTask("consumer", "weather", "forecast", List.of("source"), List.of(),
                List.of(new InputBinding("city", InputBinding.Source.DEPENDENCY_OUTPUT, null, "source", "/city"),
                        parentBinding("date", "tomorrow")), List.of("return the forecast"), "v1");
        WorkerContractValidator validator = new WorkerContractValidator(json);

        JsonNode resolved = validator.resolveInput(worker, consumer, "unused raw request",
                java.util.Map.of("source", dependency));

        assertEquals("Paris", resolved.path("city").asText());
        assertEquals("tomorrow", resolved.path("date").asText());
        assertFalse(resolved.has("privateNote"));
    }

    @Test
    void planRejectsDependencyPointerNotDeclaredByProducerOutputSchema() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        registry.register(worker());
        PlannedTask source = new PlannedTask("source", "weather", "source forecast", List.of(), List.of("source"),
                List.of(parentBinding("city", "Paris"), parentBinding("date", "today")),
                List.of("return forecast"), null);
        PlannedTask consumer = new PlannedTask("consumer", "weather", "use source", List.of("source"), List.of("result"),
                List.of(new InputBinding("city", InputBinding.Source.DEPENDENCY_OUTPUT,
                        null, "source", "/privateNote"), parentBinding("date", "tomorrow")),
                List.of("return forecast"), null);
        TaskPlan plan = new TaskPlan(TaskPlan.Route.DELEGATE, List.of(source, consumer), List.of(
                new TaskPlan.Deliverable("source", "source forecast"),
                new TaskPlan.Deliverable("result", "derived result")));

        WorkerContractException failure = assertThrows(WorkerContractException.class,
                () -> new TaskPlanValidator(registry, 4).validate(plan));
        assertEquals("JSON_POINTER_NOT_DECLARED", failure.issues().getFirst().reasonCode());
    }

    private WorkerSpec worker() throws Exception {
        return new WorkerSpec("weather", "Weather worker", "private worker system prompt", List.of(), 3, 4,
                "v1", "forecast weather", json.readTree("""
                {"type":"object","properties":{"city":{"type":"string"},"date":{"type":"string"}},
                 "required":["city","date"],"additionalProperties":false}
                """), "city and date", "forecast data", json.readTree("""
                {"type":"object","properties":{"city":{"type":"string"},"forecast":{"type":"string"}},
                 "required":["city","forecast"],"additionalProperties":false}
                """));
    }

    private static InputBinding parentBinding(String field, String value) {
        return new InputBinding(field, InputBinding.Source.PARENT_REQUEST,
                new ObjectMapper().getNodeFactory().textNode(value), null, null);
    }

    private static TaskPlan plan(List<InputBinding> bindings) {
        PlannedTask task = new PlannedTask("task", "weather", "forecast", List.of(), List.of("forecast"),
                bindings, List.of("return a forecast for the requested place and date"), null);
        return new TaskPlan(TaskPlan.Route.DELEGATE, List.of(task),
                List.of(new TaskPlan.Deliverable("forecast", "weather forecast")));
    }
}
