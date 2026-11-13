package com.example.agent.evaluation;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;

/** Explicit limits for a manually started live-provider evaluation run. */
public record AgentEvaluationConfig(boolean enabled, int maxCases, int maxModelCalls, int maxToolCalls,
                                   Duration totalTimeout, Path reportDirectory,
                                   BigDecimal inputUsdPerMillionTokens,
                                   BigDecimal outputUsdPerMillionTokens) {
    public AgentEvaluationConfig {
        if (maxCases < 1 || maxCases > 100 || maxModelCalls < 1 || maxToolCalls < 0
                || totalTimeout == null || totalTimeout.isNegative() || totalTimeout.isZero()
                || reportDirectory == null || reportDirectory.toString().isBlank()) {
            throw new IllegalArgumentException("Invalid evaluation budget or output configuration");
        }
        boolean hasInputRate = inputUsdPerMillionTokens != null;
        boolean hasOutputRate = outputUsdPerMillionTokens != null;
        if (hasInputRate != hasOutputRate
                || hasInputRate && (inputUsdPerMillionTokens.signum() < 0 || outputUsdPerMillionTokens.signum() < 0)) {
            throw new IllegalArgumentException("Both non-negative token price rates must be configured together");
        }
    }
}
