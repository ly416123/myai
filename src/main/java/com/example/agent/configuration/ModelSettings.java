package com.example.agent.configuration;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Non-sensitive model settings. Provider credentials are represented only by a secret reference. */
public record ModelSettings(String provider, String model, Map<String, Object> parameters, String credentialRef) {
    private static final Set<String> ALLOWED_PARAMETERS = Set.of("temperature", "top_p", "max_tokens", "seed");

    public ModelSettings {
        if (provider == null || !provider.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")) {
            throw new IllegalArgumentException("Model provider is invalid");
        }
        if (model == null || !model.matches("[a-zA-Z0-9][a-zA-Z0-9._/-]{0,119}")) {
            throw new IllegalArgumentException("Model ID is invalid");
        }
        if (credentialRef == null || !credentialRef.matches("secret://[a-zA-Z0-9][a-zA-Z0-9._/-]{0,159}")) {
            throw new IllegalArgumentException("Model credential must be a secret reference");
        }
        Map<String, Object> copy = new TreeMap<>();
        if (parameters != null) {
            parameters.forEach((name, value) -> {
                if (!ALLOWED_PARAMETERS.contains(name) || !(value instanceof Number)) {
                    throw new IllegalArgumentException("Model parameter is unsupported or invalid");
                }
                BigDecimal numeric = new BigDecimal(value.toString());
                if (name.equals("temperature") && (numeric.compareTo(BigDecimal.ZERO) < 0
                        || numeric.compareTo(BigDecimal.valueOf(2)) > 0)) {
                    throw new IllegalArgumentException("Model temperature is outside the supported range");
                }
                if (name.equals("top_p") && (numeric.compareTo(BigDecimal.ZERO) <= 0
                        || numeric.compareTo(BigDecimal.ONE) > 0)) {
                    throw new IllegalArgumentException("Model top_p is outside the supported range");
                }
                if ((name.equals("max_tokens") || name.equals("seed")) && numeric.stripTrailingZeros().scale() > 0) {
                    throw new IllegalArgumentException("Integer model parameter is invalid");
                }
                copy.put(name, numeric.stripTrailingZeros());
            });
        }
        parameters = Collections.unmodifiableMap(copy);
    }
}
