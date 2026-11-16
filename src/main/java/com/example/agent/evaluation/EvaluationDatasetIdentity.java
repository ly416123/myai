package com.example.agent.evaluation;

public record EvaluationDatasetIdentity(String datasetId, String datasetVersion, String contentHash, int formatVersion) {
    public EvaluationDatasetIdentity {
        if (datasetId == null || datasetId.isBlank() || datasetVersion == null || datasetVersion.isBlank()
                || contentHash == null || !contentHash.matches("[a-f0-9]{64}") || formatVersion < 1)
            throw new IllegalArgumentException("Invalid evaluation dataset identity");
    }
}
