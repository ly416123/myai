package com.example.agent.observability;

import com.example.agent.workflow.FailureCategory;
import com.example.agent.workflow.RunRecord;
import com.example.agent.workflow.RunQueueSnapshot;
import com.example.agent.workflow.RunType;
import com.example.agent.workflow.RunStep;
import com.example.agent.workflow.RunStepStatus;
import com.example.agent.workflow.StepKind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AgentTelemetryTest {
    @Test
    void publishesBacklogLeaseAndOldestWaitGaugesWithoutRunIdentifiers() {
        var meters = new SimpleMeterRegistry();
        var telemetry = new AgentTelemetry(meters, ObservationRegistry.create());

        telemetry.recordQueueSnapshot(new RunQueueSnapshot(7, 2, 3, Duration.ofSeconds(45)));

        assertEquals(7.0, meters.get("agent.worker.queue.depth").gauge().value());
        assertEquals(2.0, meters.get("agent.worker.lease.expired").gauge().value());
        assertEquals(3.0, meters.get("agent.worker.run.failed.recent").gauge().value());
        assertEquals(45.0, meters.get("agent.worker.queue.oldest").gauge().value(), 0.1);
        meters.getMeters().forEach(meter -> assertTrue(meter.getId().getTags().stream()
                .noneMatch(tag -> tag.getKey().equalsIgnoreCase("runId")
                        || tag.getKey().equalsIgnoreCase("stepId"))));
    }

    @Test
    void recordsWorkerQueueWaitWithOnlyBoundedTags() {
        var meters = new SimpleMeterRegistry();
        var telemetry = new AgentTelemetry(meters, ObservationRegistry.create());

        telemetry.recordWorkerDispatch(RunType.COLLABORATIVE, Duration.ofMillis(37));

        assertEquals(1.0, meters.get("agent.worker.dispatches").tag("run_type", "COLLABORATIVE")
                .counter().count());
        assertEquals(1, meters.get("agent.worker.queue.wait").tag("run_type", "COLLABORATIVE")
                .timer().count());
        assertEquals(37.0, meters.get("agent.worker.queue.wait").tag("run_type", "COLLABORATIVE")
                .timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS), 0.1);
        assertTrue(meters.get("agent.worker.dispatches").meters().iterator().next().getId().getTags().stream()
                .noneMatch(tag -> tag.getKey().equalsIgnoreCase("runId") || tag.getKey().equalsIgnoreCase("stepId")));
    }

    @Test
    void recordsStepMetricsWithBoundedTagsAndActualTokenUsage() {
        var meters = new SimpleMeterRegistry();
        var telemetry = new AgentTelemetry(meters, ObservationRegistry.create());
        RunRecord run = RunRecord.create("sensitive-run-id", "private request", Duration.ofMinutes(1));
        Instant start = Instant.now();
        RunStep step = RunStep.modelStarted("sensitive-step-id", start)
                .finish(RunStepStatus.SUCCEEDED, start.plusMillis(25), null, 12L, 7L);
        var observation = telemetry.startStep(run, step, "single");

        telemetry.finishStep(observation, step, "single");

        assertEquals(1.0, meters.get("agent.model.calls").tag("workflow", "single")
                .tag("status", "SUCCEEDED").counter().count());
        assertEquals(12.0, meters.get("agent.model.tokens").tag("direction", "prompt").counter().count());
        assertEquals(7.0, meters.get("agent.model.tokens").tag("direction", "completion").counter().count());
        assertTrue(meters.get("agent.model.calls").meters().iterator().next().getId().getTags().stream()
                .noneMatch(tag -> tag.getValue().contains("sensitive-run-id") || tag.getValue().contains("sensitive-step-id")));
    }

    @Test
    void marksMissingTokenUsageAsUnknownInsteadOfEstimating() {
        var meters = new SimpleMeterRegistry();
        var telemetry = new AgentTelemetry(meters, ObservationRegistry.create());
        RunRecord run = RunRecord.create("run", "input", Duration.ofMinutes(1));
        Instant start = Instant.now();
        RunStep step = RunStep.modelStarted("step", start)
                .finish(RunStepStatus.FAILED, start.plusMillis(2), FailureCategory.MODEL_NETWORK, null, null);

        telemetry.finishStep(telemetry.startStep(run, step, "single"), step, "single");

        assertEquals(1.0, meters.get("agent.model.token_usage.unknown").counter().count());
        assertTrue(meters.find("agent.model.tokens").meters().isEmpty());
    }
}
