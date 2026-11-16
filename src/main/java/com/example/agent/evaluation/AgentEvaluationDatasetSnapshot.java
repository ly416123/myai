package com.example.agent.evaluation;

/** Immutable, content-addressed identity for one loaded evaluation dataset. */
public record AgentEvaluationDatasetSnapshot(String datasetId, int formatVersion,
                                             String contentSha256, AgentEvaluationDataset dataset) {
    public AgentEvaluationDatasetSnapshot {
        if (datasetId == null || !datasetId.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")
                || formatVersion < 1 || contentSha256 == null || !contentSha256.matches("[a-f0-9]{64}")
                || dataset == null) {
            throw new IllegalArgumentException("Invalid evaluation dataset snapshot");
        }
    }

    public String datasetVersion() { return dataset.version(); }
}
