package com.example.agent;

import com.example.agent.client.DeepSeekModelClient;
import com.example.agent.runtime.AgentRuntime;
import com.example.agent.tool.ToolExecutor;
import com.example.agent.tool.ToolRegistry;
import com.example.agent.tools.CalculatorTool;
import com.example.agent.tools.TimeTool;
import com.example.agent.tools.WeatherTool;
import com.example.agent.workflow.JsonFileRunStore;
import com.example.agent.workflow.RunRecord;
import com.example.agent.workflow.RunStatus;
import com.example.agent.workflow.RuntimeConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Scanner;

/**
 * 命令行交互入口，负责读取配置、组装依赖并将用户输入交给 AgentRuntime。
 *
 * <p>Main 是应用的 composition root：在这里创建工具、注册表、模型客户端、执行器和 Runtime。
 * AgentRuntime 本身不读取环境变量、不创建 SDK 客户端，因而可在单元测试中注入假 ModelClient。
 */
public final class Main {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private Main() {}

    /**
     * 读取密钥优先级：环境变量 DEEPSEEK_API_KEY，其次是 DEEPSEEK_KEY_FILE 指定的文件，
     * 默认文件名为项目根目录 deepseek.key。只返回密钥给客户端使用，不写日志。
     */
    private static String loadApiKey() throws IOException {
        String fromEnvironment = System.getenv("DEEPSEEK_API_KEY");
        if (fromEnvironment != null && !fromEnvironment.isBlank()) return fromEnvironment.trim();
        Path keyFile = Path.of(System.getenv().getOrDefault("DEEPSEEK_KEY_FILE", "deepseek.key"));
        return Files.exists(keyFile) ? Files.readString(keyFile).trim() : "";
    }

    /** 启动 CLI；支持查看运行记录和恢复未完成的任务。 */
    public static void main(String[] args) {
        String apiKey;
        try {
            apiKey = loadApiKey();
        } catch (IOException e) {
            System.err.println("无法读取 DeepSeek API Key 文件。");
            return;
        }
        String model = System.getenv().getOrDefault("DEEPSEEK_MODEL", "deepseek-flash");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("请将 DeepSeek API Key 写入项目根目录 deepseek.key，或设置 DEEPSEEK_API_KEY 环境变量。");
            return;
        }

        // 集中组装运行依赖。默认检查点目录可通过环境变量调整，目录本身已在 .gitignore 中排除。
        var registry = new ToolRegistry();
        registry.register(new CalculatorTool());
        registry.register(new TimeTool());
        registry.register(new WeatherTool());
        Path runDirectory = Path.of(System.getenv().getOrDefault("AGENT_RUN_STORE", ".agent-runs"));
        var runStore = new JsonFileRunStore(runDirectory, JSON);

        // 模型调用数包含重试；工具调用总数、总 Deadline 和重试数均由同一配置约束。
        var config = new RuntimeConfig(10, 20, 2, Duration.ofMinutes(5));
        try (var modelClient = new DeepSeekModelClient(apiKey, model, Duration.ofSeconds(60));
             var executor = new ToolExecutor(registry, Duration.ofSeconds(10));
             var scanner = new Scanner(System.in)) {
            var runtime = new AgentRuntime(modelClient, registry, executor, runStore, config);
            System.out.println("Java Agent Workflow 已启动。输入 /runs 查看记录，/resume <runId> 恢复未完成任务，/exit 结束。\n");

            while (true) {
                System.out.print("你> ");
                if (!scanner.hasNextLine()) break;
                String input = scanner.nextLine().trim();
                if (input.equalsIgnoreCase("/exit") || input.equalsIgnoreCase("/quit")) break;
                if (input.isEmpty()) continue;

                if (input.equalsIgnoreCase("/runs")) {
                    runStore.list().forEach(Main::printRunSummary);
                    System.out.println();
                    continue;
                }

                try {
                    if (input.startsWith("/resume ")) {
                        String runId = input.substring("/resume ".length()).trim();
                        printRunResult(runtime.resume(runId));
                    } else {
                        // 每条输入都会创建稳定 runId，并在返回前持久化终态或可恢复检查点。
                        printRunResult(runtime.runTracked(input));
                    }
                } catch (Exception e) {
                    // 不展示原始异常正文，避免供应商错误或底层异常意外包含凭据。
                    System.err.println("操作未完成；请用 /runs 查看运行记录。");
                }
                System.out.println();
            }
        }
        System.out.println("已退出。");
    }

    private static void printRunResult(RunRecord run) {
        System.out.printf("[runId=%s status=%s modelCalls=%d toolCalls=%d resumed=%s durationMs=%d]%n",
                run.runId(), run.status(), run.modelCalls(), run.toolCalls(), run.resumed(), run.totalDurationMillis());
        if (run.status() == RunStatus.SUCCEEDED) {
            System.out.println("Agent> " + run.finalAnswer());
        } else {
            System.err.println("本轮结束：" + run.failureCategory());
        }
    }

    private static void printRunSummary(RunRecord run) {
        String tokens = run.tokenUsageKnown()
                ? "prompt=" + run.promptTokens() + ", completion=" + run.completionTokens()
                : "unknown";
        System.out.printf("runId=%s status=%s modelCalls=%d toolCalls=%d durationMs=%d resumed=%s tokens=%s%n",
                run.runId(), run.status(), run.modelCalls(), run.toolCalls(), run.totalDurationMillis(),
                run.resumed(), tokens);
    }
}
