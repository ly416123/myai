package com.example.agent.evaluation;

import java.util.List;
import java.time.Instant;

public record EvaluationSnapshot(EvaluationRunRecord run, List<EvaluationCaseRecord> cases,
                                 List<EvaluationStatusEvent> statusEvents, Instant leaseExpiresAt) {
    public EvaluationSnapshot(EvaluationRunRecord run, List<EvaluationCaseRecord> cases) {
        this(run, cases, List.of(), null);
    }
    public EvaluationSnapshot(EvaluationRunRecord run, List<EvaluationCaseRecord> cases,
                             List<EvaluationStatusEvent> statusEvents) {
        this(run, cases, statusEvents, null);
    }
    public EvaluationSnapshot { cases = List.copyOf(cases); statusEvents = List.copyOf(statusEvents); }
}
