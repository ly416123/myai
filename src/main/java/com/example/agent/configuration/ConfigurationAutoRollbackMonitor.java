package com.example.agent.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Periodically checks the configured environment; the assignment version CAS makes duplicate schedulers safe. */
public final class ConfigurationAutoRollbackMonitor {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigurationAutoRollbackMonitor.class);
    private final AutomaticConfigurationRollback rollback;
    private final String environment;
    private final String agentId;

    public ConfigurationAutoRollbackMonitor(AutomaticConfigurationRollback rollback, String environment, String agentId) {
        this.rollback = java.util.Objects.requireNonNull(rollback);
        this.environment = java.util.Objects.requireNonNull(environment);
        this.agentId = java.util.Objects.requireNonNull(agentId);
    }

    @Scheduled(fixedDelayString = "${agent.configuration.auto-rollback.check-interval:PT30S}",
            initialDelayString = "${agent.configuration.auto-rollback.initial-delay:PT30S}")
    public void check() {
        try {
            AutomaticRollbackResult result = rollback.evaluate(environment, agentId);
            if (result.outcome() == AutomaticRollbackOutcome.ROLLED_BACK) {
                LOG.warn("configAutoRollback outcome={} environment={} agentId={} samples={} failures={} failureRate={}",
                        result.outcome(), environment, agentId, result.samples(), result.failures(), result.failureRate());
            } else if (result.outcome() != AutomaticRollbackOutcome.NO_CANDIDATE
                    && result.outcome() != AutomaticRollbackOutcome.DISABLED) {
                LOG.debug("configAutoRollback outcome={} environment={} agentId={} samples={} failures={}",
                        result.outcome(), environment, agentId, result.samples(), result.failures());
            }
        } catch (RuntimeException failure) {
            // Avoid logging exception messages that can contain SQL or configuration payload fragments.
            LOG.error("configAutoRollback failed failureType={}", failure.getClass().getSimpleName());
        }
    }
}
