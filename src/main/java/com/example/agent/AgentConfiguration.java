package com.example.agent;

import com.example.agent.client.DeepSeekModelClient;
import com.example.agent.client.ModelClient;
import com.example.agent.collaboration.CollaborationConfig;
import com.example.agent.collaboration.CollaborativeWorkflow;
import com.example.agent.collaboration.WorkerRegistry;
import com.example.agent.collaboration.WorkerSpec;
import com.example.agent.runtime.AgentRuntime;
import com.example.agent.observability.AgentTelemetry;
import com.example.agent.tool.ToolExecutor;
import com.example.agent.tool.ToolRegistry;
import com.example.agent.tools.CalculatorTool;
import com.example.agent.tools.TimeTool;
import com.example.agent.tools.WeatherTool;
import com.example.agent.workflow.PostgresRunStore;
import com.example.agent.workflow.RunStore;
import com.example.agent.workflow.RunPollingWorker;
import com.example.agent.app.RunSubmissionPolicy;
import com.example.agent.app.RunSubmissionService;
import com.example.agent.evaluation.AgentEvaluationConfig;
import com.example.agent.evaluation.AgentEvaluationRunner;
import com.example.agent.evaluation.EvaluationRepository;
import com.example.agent.evaluation.PostgresEvaluationRepository;
import com.example.agent.evaluation.EvaluationService;
import com.example.agent.evaluation.EvaluationEvaluatorRegistry;
import com.example.agent.workflow.RuntimeConfig;
import com.example.agent.workflow.RunLeasePolicy;
import com.example.agent.workflow.RunRetentionService;
import com.example.agent.workflow.ChildTaskPollingWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Explicit application wiring keeps the handwritten runtime independent from Spring APIs. */
@Configuration(proxyBeanMethods = false)
public class AgentConfiguration {
    @Bean
    ToolRegistry toolRegistry() {
        var registry = new ToolRegistry();
        registry.register(new CalculatorTool());
        registry.register(new TimeTool());
        registry.register(new WeatherTool());
        return registry;
    }

    @Bean(destroyMethod = "close")
    ModelClient modelClient(AgentProperties properties, Environment environment) {
        String apiKey = environment.getProperty("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            String keyFile = environment.getProperty("DEEPSEEK_KEY_FILE", properties.getApiKeyFile());
            try {
                Path path = Path.of(keyFile);
                if (Files.isRegularFile(path)) apiKey = Files.readString(path).trim();
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read configured provider credential file");
            }
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Set DEEPSEEK_API_KEY or provide a provider key file");
        }
        validate(properties);
        String baseUrl = environment.getProperty("DEEPSEEK_BASE_URL", properties.getModelBaseUrl());
        return new DeepSeekModelClient(apiKey.trim(), properties.getModel(), properties.getModelTimeout(), baseUrl,
                properties.getModelProxyUrl());
    }

    @Bean(destroyMethod = "close")
    ToolExecutor toolExecutor(ToolRegistry registry, AgentProperties properties) {
        return new ToolExecutor(registry, properties.getToolTimeout());
    }

    @Bean
    PostgresRunStore runStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new PostgresRunStore(jdbc, mapper);
    }

    @Bean
    RunRetentionService runRetentionService(RunStore store, AgentProperties properties) {
        return new RunRetentionService(store, properties.getRetentionPeriod(), properties.getRetentionBatchSize());
    }

    @Bean
    RunSubmissionPolicy runSubmissionPolicy() {
        return RunSubmissionPolicy.noAuthentication();
    }

    @Bean
    RunSubmissionService runSubmissionService(RunStore store, AgentProperties properties,
                                              RunSubmissionPolicy policy) {
        return new RunSubmissionService(store, properties.getTaskTimeout(), policy);
    }

    @Bean
    AgentEvaluationConfig agentEvaluationConfig(AgentProperties properties) {
        return new AgentEvaluationConfig(properties.isEvaluationEnabled(), properties.getEvaluationMaxCases(), properties.getEvaluationMaxModelCalls(),
                properties.getEvaluationMaxToolCalls(), properties.getEvaluationTimeout(),
                properties.getEvaluationReportDirectory(), properties.getEvaluationInputUsdPerMillionTokens(),
                properties.getEvaluationOutputUsdPerMillionTokens());
    }

    @Bean
    AgentEvaluationRunner agentEvaluationRunner(ModelClient model, ToolRegistry tools, ToolExecutor executor,
                                                WorkerRegistry workers, RunStore store, RuntimeConfig runtimeConfig,
                                                CollaborationConfig collaborationConfig, ObjectMapper mapper,
                                                AgentTelemetry telemetry) {
        return new AgentEvaluationRunner(model, tools, executor, workers, store, runtimeConfig,
                collaborationConfig, mapper, telemetry);
    }

    @Bean
    EvaluationRepository evaluationRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new PostgresEvaluationRepository(jdbc, mapper);
    }

    @Bean
    EvaluationEvaluatorRegistry evaluationEvaluatorRegistry() {
        return EvaluationEvaluatorRegistry.defaults();
    }

    @Bean
    EvaluationService evaluationService(EvaluationRepository repository, AgentEvaluationRunner runner,
                                        EvaluationEvaluatorRegistry evaluators, Environment environment,
                                        AgentTelemetry telemetry) {
        return new EvaluationService(repository, runner, evaluators, environment.getProperty("GIT_COMMIT"), telemetry);
    }

    @Bean
    AgentTelemetry agentTelemetry(MeterRegistry meters, ObservationRegistry observations) {
        return new AgentTelemetry(meters, observations);
    }

    @Bean
    RuntimeConfig runtimeConfig(AgentProperties properties) {
        return new RuntimeConfig(properties.getMaxModelCalls(), properties.getMaxToolCalls(),
                properties.getMaxModelRetries(), properties.getTaskTimeout());
    }

    @Bean
    RunLeasePolicy runLeasePolicy(AgentProperties properties) {
        return new RunLeasePolicy(properties.getRunLeaseDuration(), properties.getRunLeaseRenewalInterval());
    }

    @Bean(destroyMethod = "close")
    AgentRuntime agentRuntime(ModelClient model, ToolRegistry tools, ToolExecutor executor,
                              RunStore store, RuntimeConfig runtimeConfig, AgentTelemetry telemetry,
                              RunLeasePolicy leasePolicy) {
        return new AgentRuntime(model, tools, executor, store, runtimeConfig, telemetry, leasePolicy);
    }

    @Bean
    WorkerRegistry workerRegistry() {
        var registry = new WorkerRegistry();
        registry.register(new WorkerSpec("calculator", "本地四则运算", "你负责执行用户拆分给你的计算任务。只报告计算结果和必要步骤。",
                java.util.List.of("calculator"), 4, 8, "v1", "本地加减乘除计算",
                contractSchema("""
                        {"type":"object","properties":{"expression":{"type":"string","description":"待计算的数学表达式"}},
                         "required":["expression"],"additionalProperties":false}
                        """), "待计算的数学表达式", "操作符、操作数与计算结果",
                contractSchema("""
                        {"type":"object","properties":{"operation":{"type":"string"},"a":{"type":"number"},
                         "b":{"type":"number"},"result":{"type":"number"}},
                         "required":["operation","a","b","result"],"additionalProperties":false}
                        """)));
        registry.register(new WorkerSpec("clock", "指定时区的当前时间", "你负责查询用户指定时区的当前时间。只根据工具结果回答。",
                java.util.List.of("current_time"), 3, 4, "v1", "查询指定 IANA 时区的当前时间",
                contractSchema("""
                        {"type":"object","properties":{"timezone":{"type":"string","description":"IANA 时区名称"}},
                         "required":["timezone"],"additionalProperties":false}
                        """), "IANA 时区名称，例如 Asia/Shanghai", "时区和 ISO-8601 当前时间",
                contractSchema("""
                        {"type":"object","properties":{"timezone":{"type":"string"},"dateTime":{"type":"string"},
                         "source":{"type":"string"},"simulated":{"type":"boolean"}},
                         "required":["timezone","dateTime","source","simulated"],"additionalProperties":false}
                        """)));
        registry.register(new WorkerSpec("weather", "模拟天气查询", "你负责查询指定城市的模拟天气。明确说明天气数据是模拟值。",
                java.util.List.of("weather"), 3, 4, "v1", "查询城市的模拟天气",
                contractSchema("""
                        {"type":"object","properties":{"city":{"type":"string","description":"要查询天气的城市名称"}},
                         "required":["city"],"additionalProperties":false}
                        """), "城市名称", "城市、天气状况、温度、湿度、模拟标记和数据来源",
                contractSchema("""
                        {"type":"object","properties":{"city":{"type":"string"},"condition":{"type":"string"},
                         "temperatureCelsius":{"type":"number"},"humidityPercent":{"type":"number"},
                         "source":{"type":"string"},"simulated":{"type":"boolean"}},
                         "required":["city","condition","temperatureCelsius","humidityPercent","source","simulated"],
                         "additionalProperties":false}
                        """)));
        return registry;
    }

    private static com.fasterxml.jackson.databind.JsonNode contractSchema(String schema) {
        try {
            return new ObjectMapper().readTree(schema);
        } catch (IOException invalidConfiguration) {
            throw new IllegalStateException("Invalid static Worker contract Schema");
        }
    }

    @Bean
    CollaborationConfig collaborationConfig(AgentProperties properties) {
        return new CollaborationConfig(properties.getMaxTasks(), properties.getMaxParallelism(),
                properties.getMaxModelCalls(), properties.getMaxToolCalls(), properties.getMaxModelRetries(),
                properties.getTaskTimeout(), properties.getToolTimeout(), properties.isSeparateTaskWorkers());
    }

    @Bean(destroyMethod = "close")
    CollaborativeWorkflow collaborativeWorkflow(ModelClient model, ToolRegistry tools, WorkerRegistry workers,
                                                RunStore store, CollaborationConfig config, ObjectMapper mapper,
                                                AgentTelemetry telemetry, RunLeasePolicy leasePolicy) {
        return new CollaborativeWorkflow(model, tools, workers, store, config, mapper, telemetry, leasePolicy);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "agent", name = "execution-mode", havingValue = "worker")
    RunPollingWorker runPollingWorker(RunStore store, AgentRuntime runtime, CollaborativeWorkflow collaborative,
                                      AgentProperties properties, AgentTelemetry telemetry) {
        if (properties.getWorkerExpectedInstances() < 1) {
            throw new IllegalStateException("Expected worker instance count must be positive");
        }
        telemetry.configureRunWorkers(properties.getWorkerExpectedInstances());
        return new RunPollingWorker(store, new RunPollingWorker.RunRecoveryHandler() {
            @Override
            public com.example.agent.workflow.RunRecord resume(com.example.agent.workflow.RunRecord run) {
                if (run.runType() == com.example.agent.workflow.RunType.COLLABORATIVE) return collaborative.resume(run.runId());
                return runtime.resume(run.runId());
            }

            @Override
            public void cancel(String runId, com.example.agent.workflow.RunType type) {
                if (type == com.example.agent.workflow.RunType.COLLABORATIVE) collaborative.cancelRun(runId);
                else runtime.cancelRun(runId);
            }
        }, properties.getWorkerConcurrency(), properties.getWorkerPollInterval(),
                telemetry);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnExpression("'${agent.execution-mode:interactive}' == 'worker' && "
            + "'${agent.separate-task-workers:false}' == 'true'")
    ChildTaskPollingWorker childTaskPollingWorker(RunStore store, CollaborativeWorkflow collaborative,
                                                  WorkerRegistry workers, AgentProperties properties) {
        if (!properties.isSeparateTaskWorkers()) {
            throw new IllegalStateException("Separate child task worker must be explicitly enabled");
        }
        return new ChildTaskPollingWorker(store, collaborative, workers,
                properties.getWorkerConcurrency(), properties.getWorkerPollInterval());
    }


    private static void validate(AgentProperties properties) {
        if (properties.getModel() == null || properties.getModel().isBlank()
                || properties.getModelTimeout() == null || properties.getModelTimeout().isNegative()
                || properties.getModelTimeout().isZero() || properties.getToolTimeout() == null
                || properties.getToolTimeout().isNegative() || properties.getToolTimeout().isZero()
                || properties.getTaskTimeout() == null || properties.getTaskTimeout().isNegative()
                || properties.getTaskTimeout().isZero() || properties.getMaxModelCalls() < 1
                || properties.getMaxToolCalls() < 0 || properties.getMaxModelRetries() < 0
                || properties.getMaxTasks() < 1 || properties.getMaxParallelism() < 1
                || properties.getWorkerExpectedInstances() < 1) {
            throw new IllegalStateException("Invalid agent runtime configuration");
        }
    }
}
