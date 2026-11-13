package com.example.agent.observability;

import com.example.agent.workflow.FailureCategory;
import com.example.agent.workflow.RunRecord;
import com.example.agent.workflow.RunStep;
import com.example.agent.workflow.RunStatus;
import com.example.agent.workflow.RunType;
import com.example.agent.workflow.StepKind;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import com.example.agent.workflow.RunQueueSnapshot;

/** Bounded-cardinality metrics and trace observations for Agent/Workflow execution. */
public final class AgentTelemetry {
    private final MeterRegistry meters;
    private final ObservationRegistry observations;
    private final AtomicLong queueDepth = new AtomicLong();
    private final AtomicLong expiredLeases = new AtomicLong();
    private final AtomicLong recentFailedRuns = new AtomicLong();
    private final AtomicLong oldestQueueWaitMillis = new AtomicLong();

    public AgentTelemetry(MeterRegistry meters, ObservationRegistry observations) {
        this.meters = meters;
        this.observations = observations == null ? ObservationRegistry.NOOP : observations;
        if (meters != null) {
            io.micrometer.core.instrument.Gauge.builder("agent.worker.queue.depth", queueDepth,
                            AtomicLong::get).baseUnit("runs").register(meters);
            io.micrometer.core.instrument.Gauge.builder("agent.worker.lease.expired", expiredLeases,
                            AtomicLong::get).baseUnit("leases").register(meters);
            io.micrometer.core.instrument.Gauge.builder("agent.worker.run.failed.recent", recentFailedRuns,
                            AtomicLong::get).baseUnit("runs").register(meters);
            io.micrometer.core.instrument.Gauge.builder("agent.worker.queue.oldest", oldestQueueWaitMillis,
                            value -> value.get() / 1000.0).baseUnit("seconds").register(meters);
        }
    }

    public static AgentTelemetry noop() {
        return new AgentTelemetry(null, ObservationRegistry.NOOP);
    }

    public Observation startRun(RunRecord run, String workflow) {
        return Observation.createNotStarted("agent.run", observations)
                .lowCardinalityKeyValue("workflow", safeTag(workflow))
                .highCardinalityKeyValue("run.id", run.runId())
                .start();
    }

    public Observation startStep(RunRecord run, RunStep step, String workflow) {
        return Observation.createNotStarted("agent.step", observations)
                .lowCardinalityKeyValue("workflow", safeTag(workflow))
                .lowCardinalityKeyValue("step.kind", step.kind().name())
                .highCardinalityKeyValue("run.id", run.runId())
                .highCardinalityKeyValue("step.id", step.stepId())
                .highCardinalityKeyValue("task.id", safeTag(step.taskId()))
                .highCardinalityKeyValue("worker.id", safeTag(step.workerId()))
                .start();
    }

    public void finishStep(Observation observation, RunStep step, String workflow) {
        observation.lowCardinalityKeyValue("step.status", step.status().name());
        observation.lowCardinalityKeyValue("failure.category", category(step.failureCategory()));
        observation.stop();
        if (meters == null) return;

        String[] tags = {"workflow", safeTag(workflow), "kind", step.kind().name(),
                "status", step.status().name(), "failure", category(step.failureCategory())};
        meters.counter("agent.steps", tags).increment();
        Timer.builder("agent.step.duration").tags(tags).register(meters)
                .record(step.durationMillis(), TimeUnit.MILLISECONDS);
        if (isModelStep(step.kind())) {
            meters.counter("agent.model.calls", "workflow", safeTag(workflow), "status", step.status().name(),
                    "failure", category(step.failureCategory())).increment();
            if (step.promptTokens() != null && step.completionTokens() != null) {
                meters.counter("agent.model.tokens", "direction", "prompt", "workflow", safeTag(workflow))
                        .increment(step.promptTokens());
                meters.counter("agent.model.tokens", "direction", "completion", "workflow", safeTag(workflow))
                        .increment(step.completionTokens());
            } else {
                meters.counter("agent.model.token_usage.unknown", "workflow", safeTag(workflow)).increment();
            }
        }
        if (step.kind() == StepKind.TOOL) {
            meters.counter("agent.tool.calls", "workflow", safeTag(workflow), "status", step.status().name(),
                    "failure", category(step.failureCategory())).increment();
        }
    }

    public void finishRun(Observation observation, RunRecord run, String workflow) {
        observation.lowCardinalityKeyValue("run.status", run.status().name());
        observation.lowCardinalityKeyValue("failure.category", category(run.failureCategory()));
        observation.stop();
        if (meters == null) return;
        String[] tags = {"workflow", safeTag(workflow), "status", run.status().name(),
                "failure", category(run.failureCategory())};
        meters.counter("agent.runs.completed", tags).increment();
        Timer.builder("agent.run.duration").tags(tags).register(meters)
                .record(Duration.ofMillis(run.totalDurationMillis()));
        if (run.resumed()) meters.counter("agent.runs.resumed", "workflow", safeTag(workflow)).increment();
    }

    /** Records background dispatch attempts and queue delay using only the fixed Run type as a metric tag. */
    public void recordWorkerDispatch(RunType runType, Duration queueWait) {
        if (runType == null || queueWait != null && queueWait.isNegative())
            throw new IllegalArgumentException("Invalid worker metric values");
        if (meters == null) return;
        String type = runType.name();
        meters.counter("agent.worker.dispatches", "run_type", type).increment();
        if (queueWait != null)
            Timer.builder("agent.worker.queue.wait").tag("run_type", type).register(meters).record(queueWait);
    }

    /** Updates aggregate worker backlog gauges; no per-run identifiers are attached to metrics. */
    public void recordQueueSnapshot(RunQueueSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("Queue snapshot is required");
        queueDepth.set(snapshot.recoverableRuns());
        expiredLeases.set(snapshot.expiredLeases());
        recentFailedRuns.set(snapshot.recentFailedRuns());
        oldestQueueWaitMillis.set(snapshot.oldestWait().toMillis());
    }

    private static boolean isModelStep(StepKind kind) {
        return kind == StepKind.MODEL || kind == StepKind.PLAN || kind == StepKind.SUMMARY;
    }

    private static String category(FailureCategory category) {
        return category == null ? "NONE" : category.name();
    }

    private static String safeTag(String value) {
        return value == null || value.isBlank() ? "NONE" : value;
    }
}
