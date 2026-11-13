package com.example.agent.observability;

import com.example.agent.workflow.FailureCategory;
import com.example.agent.workflow.RunRecord;
import com.example.agent.workflow.RunQueueSnapshot;
import com.example.agent.workflow.RunType;
import com.example.agent.workflow.RunStep;
import com.example.agent.workflow.RunStepStatus;
import com.example.agent.workflow.StepKind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.micrometer.tracing.BaggageManager;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AgentTelemetryTest {
    @Test
    void capturesAndRestoresW3cParentAcrossWorkerBoundary() {
        var exported = new java.util.concurrent.ConcurrentLinkedQueue<SpanData>();
        var provider = SdkTracerProvider.builder().addSpanProcessor(new SpanProcessor() {
            @Override public void onStart(io.opentelemetry.context.Context parent, ReadWriteSpan span) { }
            @Override public boolean isStartRequired() { return false; }
            @Override public void onEnd(ReadableSpan span) { exported.add(span.toSpanData()); }
            @Override public boolean isEndRequired() { return true; }
        }).build();
        try {
            var currentTraceContext = new OtelCurrentTraceContext();
            var micrometerTracer = new OtelTracer(provider.get("trace-context-test"), currentTraceContext,
                    ignored -> { }, BaggageManager.NOOP);
            ObservationRegistry observations = ObservationRegistry.create();
            observations.observationConfig().observationHandler(new DefaultTracingObservationHandler(micrometerTracer));
            var telemetry = new AgentTelemetry(null, observations);
            var root = provider.get("trace-context-test").spanBuilder("external-root").startSpan();
            String traceId;
            String runSpanId;
            java.util.Map<String, String> carrier;
            RunRecord run = RunRecord.create("trace-run", "safe input", Duration.ofMinutes(1));
            RunStep worker = RunStep.workflow("worker-step", StepKind.WORKER, "task", "writer", "WORKER", Instant.now());
            RunStep model = RunStep.modelStarted("model-step", Instant.now());
            try (Scope ignored = root.makeCurrent()) {
                var runObservation = telemetry.startRun(run, "collaborative");
                try (Observation.Scope runScope = runObservation.openScope()) {
                    traceId = io.opentelemetry.api.trace.Span.current().getSpanContext().getTraceId();
                    runSpanId = io.opentelemetry.api.trace.Span.current().getSpanContext().getSpanId();
                    carrier = telemetry.captureCurrentTraceContext();
                }
                runObservation.stop();
            } finally {
                root.end();
            }
            assertTrue(carrier.containsKey("traceparent"));
            assertTrue(carrier.keySet().stream().allMatch(key ->
                    key.equals("traceparent") || key.equals("tracestate")));

            Observation workerObservation;
            try (Scope remoteParent = telemetry.restoreTraceContext(carrier)) {
                workerObservation = telemetry.startStep(run, worker, "collaborative");
            }
            try (Observation.Scope workerScope = workerObservation.openScope()) {
                Observation modelObservation = telemetry.startStep(run, model, "collaborative");
                modelObservation.stop();
            }
            workerObservation.stop();

            SpanData workerSpan = exported.stream()
                    .filter(span -> "worker-step".equals(span.getAttributes().get(AttributeKey.stringKey("step.id"))))
                    .findFirst().orElseThrow();
            SpanData modelSpan = exported.stream()
                    .filter(span -> "model-step".equals(span.getAttributes().get(AttributeKey.stringKey("step.id"))))
                    .findFirst().orElseThrow();
            assertEquals(traceId, workerSpan.getTraceId());
            assertEquals(runSpanId, workerSpan.getParentSpanId());
            assertEquals(traceId, modelSpan.getTraceId());
            assertEquals(workerSpan.getSpanId(), modelSpan.getParentSpanId());
        } finally {
            provider.close();
        }
    }

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
    void reportsExpectedRunWorkersAndSuccessfulPollHeartbeatsWithBoundedIdentity() {
        var meters = new SimpleMeterRegistry();
        var telemetry = new AgentTelemetry(meters, ObservationRegistry.create());

        telemetry.configureRunWorkers(2);
        telemetry.recordRunWorkerPollHeartbeat();

        assertEquals(2.0, meters.get("agent.worker.instances.expected")
                .tag("worker_type", "run").gauge().value());
        assertEquals(1.0, meters.get("agent.worker.poll.heartbeat")
                .tag("worker_type", "run").counter().count());
        meters.getMeters().forEach(meter -> assertTrue(meter.getId().getTags().stream()
                .allMatch(tag -> tag.getKey().equals("worker_type") && tag.getValue().equals("run"))));
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
    void recordsWorkerPollFailuresWithoutHighCardinalityTags() {
        var meters = new SimpleMeterRegistry();
        var telemetry = new AgentTelemetry(meters, ObservationRegistry.create());

        telemetry.recordWorkerPollFailure();
        telemetry.recordWorkerPollFailure();

        var counter = meters.get("agent.worker.poll.failures").counter();
        assertEquals(2.0, counter.count());
        assertTrue(counter.getId().getTags().isEmpty());
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
