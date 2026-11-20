package com.example.agent.client;

import com.example.agent.configuration.ModelSettings;

import java.util.Map;

/** Per-Run model selection and non-sensitive generation parameters. */
public record ModelRequestOptions(String model, Map<String, Object> parameters) {
    public ModelRequestOptions {
        if (model == null || model.isBlank()) throw new IllegalArgumentException("Model ID is required");
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    public static ModelRequestOptions from(ModelSettings settings) {
        return new ModelRequestOptions(settings.model(), settings.parameters());
    }
}
