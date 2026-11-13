package com.example.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loads a version-controlled evaluation fixture without logging its prompt text. */
public final class AgentEvaluationDatasetLoader {
    private AgentEvaluationDatasetLoader() { }

    public static AgentEvaluationDataset load(Path path, ObjectMapper mapper) {
        if (path == null || mapper == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Evaluation dataset file is unavailable");
        }
        try {
            AgentEvaluationDataset dataset = mapper.readValue(path.toFile(), AgentEvaluationDataset.class);
            if (dataset == null) throw new IllegalArgumentException("Evaluation dataset is empty");
            return dataset;
        } catch (IOException failure) {
            // Avoid exposing fixture text, file contents, or parser excerpts in process logs.
            throw new IllegalArgumentException("Evaluation dataset file is invalid");
        }
    }
}
