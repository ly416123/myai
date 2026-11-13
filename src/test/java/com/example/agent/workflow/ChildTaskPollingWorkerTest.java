package com.example.agent.workflow;

import com.example.agent.client.ModelClient;
import com.example.agent.collaboration.ChildTaskRecord;
import com.example.agent.collaboration.CollaborationConfig;
import com.example.agent.collaboration.CollaborativeWorkflow;
import com.example.agent.collaboration.PlannedTask;
import com.example.agent.collaboration.TaskPlan;
import com.example.agent.collaboration.TaskStatus;
import com.example.agent.collaboration.WorkerRegistry;
import com.example.agent.collaboration.WorkerSpec;
import com.example.agent.model.ModelResponse;
import com.example.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChildTaskPollingWorkerTest {
    @Test
    void rejectsSubMillisecondPollingIntervalBeforeScheduling() throws Exception {
        InMemoryRunStore store = new InMemoryRunStore();
        WorkerRegistry workers = new WorkerRegistry();
        CollaborationConfig config = new CollaborationConfig(2, 1, 2, 0, 0,
                Duration.ofSeconds(10), Duration.ofSeconds(1));
        try (CollaborativeWorkflow workflow = new CollaborativeWorkflow(
                (messages, definitions) -> new ModelResponse("done", List.of()), new ToolRegistry(), workers,
                store, config, new ObjectMapper().findAndRegisterModules())) {
            assertThrows(IllegalArgumentException.class, () -> new ChildTaskPollingWorker(
                    store, workflow, workers, 1, Duration.ofNanos(1)));
        }
    }

    @Test
    void supervisorWaitsForTasksExecutedThroughTheSeparateWorkerQueue() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        InMemoryRunStore store = new InMemoryRunStore();
        WorkerRegistry workers = new WorkerRegistry();
        workers.register(new WorkerSpec("writer", "write", "writer prompt", List.of(), 3, 0));
        ModelClient model = new ModelClient() {
            private int call;
            @Override
            public synchronized ModelResponse complete(List<com.example.agent.model.Message> messages,
                                                       List<com.example.agent.model.ToolDefinition> definitions) {
                return switch (call++) {
                    case 0 -> new ModelResponse("{\"route\":\"DELEGATE\",\"tasks\":["
                            + "{\"taskId\":\"task-one\",\"workerId\":\"writer\","
                            + "\"description\":\"write the requested result\",\"dependencies\":[],"
                            + "\"deliverableIds\":[\"result\"]}],\"deliverables\":["
                            + "{\"deliverableId\":\"result\",\"description\":\"result\"}]}", List.of());
                    case 1 -> new ModelResponse("worker-result", List.of());
                    case 2 -> new ModelResponse("final-summary", List.of());
                    default -> throw new AssertionError("Unexpected model call");
                };
            }
        };
        ToolRegistry tools = new ToolRegistry();
        CollaborationConfig config = new CollaborationConfig(4, 1, 6, 0, 0,
                Duration.ofSeconds(10), Duration.ofSeconds(1), true);
        try (CollaborativeWorkflow workflow = new CollaborativeWorkflow(model, tools, workers, store, config, mapper);
             ChildTaskPollingWorker taskWorker = new ChildTaskPollingWorker(store, workflow, workers, 1,
                     Duration.ofMillis(10));
             var parent = Executors.newSingleThreadExecutor()) {
            taskWorker.start();
            RunRecord result = parent.submit(() -> workflow.runTracked("complete requested result"))
                    .get(5, TimeUnit.SECONDS);

            assertEquals(RunStatus.SUCCEEDED, result.status());
            assertEquals("final-summary", result.finalAnswer());
            assertEquals(TaskStatus.SUCCEEDED, result.childTasks().getFirst().status());
            assertEquals(3, result.modelCalls());
        }
    }

    @Test
    void durableCancellationInterruptsAnExternalWorkerModelWaitBeforeParentCompletes() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        InMemoryRunStore store = new InMemoryRunStore();
        PlannedTask planned = new PlannedTask("task-cancel", "writer", "wait for a result", List.of(), List.of("result"));
        RunRecord checkpoint = RunRecord.create("cancel-separate-child", "delegate this", Duration.ofSeconds(10),
                RunType.COLLABORATIVE);
        checkpoint.setTaskPlan(new TaskPlan(TaskPlan.Route.DELEGATE, List.of(planned), List.of()));
        checkpoint.addChildTask(ChildTaskRecord.pending(planned));
        checkpoint.transitionTo(RunStatus.RUNNING, Instant.now(), null);
        store.save(checkpoint);
        WorkerRegistry workers = new WorkerRegistry();
        workers.register(new WorkerSpec("writer", "write", "writer prompt", List.of(), 3, 0));
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch modelInterrupted = new CountDownLatch(1);
        ModelClient model = (messages, definitions) -> {
            modelStarted.countDown();
            try {
                new CountDownLatch(1).await();
                return new ModelResponse("unexpected", List.of());
            } catch (InterruptedException interrupted) {
                modelInterrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted by durable cancellation");
            }
        };
        CollaborationConfig config = new CollaborationConfig(4, 1, 6, 0, 0,
                Duration.ofSeconds(10), Duration.ofMillis(500), true);
        ToolRegistry tools = new ToolRegistry();
        try (CollaborativeWorkflow workflow = new CollaborativeWorkflow(model, tools, workers, store, config, mapper);
             ChildTaskPollingWorker taskWorker = new ChildTaskPollingWorker(store, workflow, workers, 1,
                     Duration.ofMillis(10));
             var parent = Executors.newSingleThreadExecutor()) {
            taskWorker.start();
            var result = parent.submit(() -> workflow.resume(checkpoint.runId()));
            assertTrue(modelStarted.await(2, TimeUnit.SECONDS));
            workflow.cancelRun(checkpoint.runId());

            RunRecord cancelled = result.get(3, TimeUnit.SECONDS);
            assertTrue(modelInterrupted.await(1, TimeUnit.SECONDS));
            assertEquals(RunStatus.CANCELLED, cancelled.status());
            assertEquals(TaskStatus.FAILED, store.find(checkpoint.runId()).orElseThrow()
                    .childTasks().getFirst().status());
        }
    }

    @Test
    void separateWorkerClaimsOnlyReadyTasksAndCompletesDependenciesInOrder() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        InMemoryRunStore store = new InMemoryRunStore();
        PlannedTask first = new PlannedTask("task-first", "writer", "produce source result", List.of(), List.of("source"));
        PlannedTask second = new PlannedTask("task-second", "writer", "use source result", List.of("task-first"), List.of("final"));
        RunRecord run = RunRecord.create("separate-child-worker", "delegate this", Duration.ofSeconds(10),
                RunType.COLLABORATIVE);
        run.setTaskPlan(new TaskPlan(TaskPlan.Route.DELEGATE, List.of(first, second), List.of()));
        run.addChildTask(ChildTaskRecord.pending(first));
        run.addChildTask(ChildTaskRecord.pending(second));
        run.transitionTo(RunStatus.RUNNING, Instant.now(), null);
        store.save(run);
        store.claim(run.runId(), "supervisor", Instant.now(), Duration.ofSeconds(30)).orElseThrow();

        WorkerRegistry workers = new WorkerRegistry();
        workers.register(new WorkerSpec("writer", "write", "writer prompt", List.of(), 3, 0));
        ModelClient model = (messages, definitions) -> new ModelResponse("done", List.of(), 2L, 1L);
        ToolRegistry tools = new ToolRegistry();
        CollaborationConfig config = new CollaborationConfig(4, 1, 8, 0, 0,
                Duration.ofSeconds(10), Duration.ofSeconds(1));
        try (CollaborativeWorkflow workflow = new CollaborativeWorkflow(model, tools, workers, store, config, mapper);
             ChildTaskPollingWorker taskWorker = new ChildTaskPollingWorker(store, workflow, workers, 1,
                     Duration.ofMillis(10))) {
            taskWorker.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            RunRecord current;
            do {
                current = store.find(run.runId()).orElseThrow();
                if (current.childTasks().stream().allMatch(task -> task.status() == TaskStatus.SUCCEEDED)) break;
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            current = store.find(run.runId()).orElseThrow();

            assertEquals(List.of(TaskStatus.SUCCEEDED, TaskStatus.SUCCEEDED), current.childTasks().stream()
                    .map(ChildTaskRecord::status).toList());
            assertTrue(current.childTasks().stream().allMatch(task -> task.messages().stream()
                    .anyMatch(message -> "done".equals(message.content()))));
            assertTrue(current.steps().stream().filter(step -> step.kind() == StepKind.MODEL)
                    .allMatch(step -> step.taskId() != null));
        }
    }
}
