package com.example.agent.client;

import com.example.agent.model.Message;
import com.example.agent.model.ModelResponse;
import com.example.agent.model.ToolCall;
import com.example.agent.model.ToolDefinition;
import com.example.agent.workflow.FailureCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.net.SocketTimeoutException;

/**
 * DeepSeek Chat Completions 适配器，使用 OpenAI Java SDK 调用 DeepSeek 的兼容接口。
 *
 * <p>本类负责三类转换：
 * <ol>
 *   <li>把内部 Message、ToolDefinition 转换成 SDK 请求结构。</li>
 *   <li>通过 HTTP 请求 DeepSeek，并应用传入的超时时间。</li>
 *   <li>把模型文本和工具调用还原成 ModelResponse、ToolCall。</li>
 * </ol>
 *
 * <p>模型生成的工具参数仍保留为 JSON，不在这里执行或解释；参数校验和 Java 方法调用
 * 属于 ToolExecutor 的职责。这个边界使厂商协议细节不会渗入 AgentRuntime。
 */
public final class DeepSeekModelClient implements ModelClient {
    private static final Logger log = LoggerFactory.getLogger(DeepSeekModelClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 底层 SDK 客户端；由本适配器创建，并在 close() 时释放。 */
    private final OpenAIClient client;
    /** API 使用的模型 ID，例如 deepseek-flash。 */
    private final String model;

    /**
     * 创建 DeepSeek 客户端。
     *
     * @param apiKey DeepSeek API Key，不应写入日志或源码
     * @param model DeepSeek 模型 ID
     * @param timeout 单次模型请求超时时间
     */
    public DeepSeekModelClient(String apiKey, String model, Duration timeout) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("DeepSeek API key is required");
        if (model == null || model.isBlank()) throw new IllegalArgumentException("DeepSeek model is required");
        this.model = model;
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl("https://api.deepseek.com")
                .timeout(timeout)
                // 禁用 SDK 内部重试，确保每次模型请求和重试都由 Runtime 计数并受预算约束。
                .maxRetries(0)
                .build();
    }

    @Override
    public ModelResponse complete(List<Message> messages, List<ToolDefinition> tools) {
        // DeepSeek 的 Chat Completions 工具调用按非思考模式发送。
        // 工具列表保持为标准 function tool 结构，使 Runtime 可继续使用厂商无关类型。
        var params = ChatCompletionCreateParams.builder().model(model)
                .putAdditionalBodyProperty("thinking", JsonValue.from(java.util.Map.of("type", "disabled")));
        for (Message message : messages) params.addMessage(toSdkMessage(message));
        for (ToolDefinition tool : tools) params.addTool(toSdkTool(tool));

        log.debug("Sending chat completion request: model={}, messages={}, tools={}", model, messages.size(), tools.size());
        final ChatCompletion completion;
        try {
            completion = client.chat().completions().create(params.build());
        } catch (RuntimeException providerFailure) {
            throw classify(providerFailure);
        }
        var message = completion.choices().getFirst().message();
        // 一个 assistant 消息可包含多个 function tool call，全部保留并按模型返回顺序处理。
        List<ToolCall> calls = message.toolCalls().orElse(List.of()).stream()
                .map(call -> call.function().map(function -> {
                    try {
                        JsonNode arguments = JSON.readTree(function.function().arguments());
                        return new ToolCall(function.id(), function.function().name(), arguments);
                    } catch (Exception e) {
                        throw new IllegalStateException("Model returned invalid JSON arguments for tool " + function.function().name(), e);
                    }
                }).orElseThrow(() -> new IllegalStateException("Model returned a non-function tool call")))
                .toList();
        Long promptTokens = completion.usage().map(usage -> usage.promptTokens()).orElse(null);
        Long completionTokens = completion.usage().map(usage -> usage.completionTokens()).orElse(null);
        return new ModelResponse(message.content().orElse(""), calls, promptTokens, completionTokens);
    }

    /** 只检查异常类型和安全的状态码类别，不把供应商错误正文或凭据写进日志/运行记录。 */
    private static ModelClientException classify(RuntimeException failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SocketTimeoutException) {
                return new ModelClientException(FailureCategory.MODEL_TIMEOUT, true);
            }
            if (current instanceof RateLimitException) {
                return new ModelClientException(FailureCategory.MODEL_RATE_LIMIT, true);
            }
            if (current instanceof InternalServerException) {
                return new ModelClientException(FailureCategory.MODEL_SERVER, true);
            }
            if (current instanceof OpenAIIoException) {
                return new ModelClientException(FailureCategory.MODEL_NETWORK, true);
            }
            if (current instanceof UnauthorizedException || current instanceof PermissionDeniedException) {
                return new ModelClientException(FailureCategory.MODEL_AUTHENTICATION, false);
            }
            if (current instanceof BadRequestException) {
                return new ModelClientException(FailureCategory.MODEL_BAD_REQUEST, false);
            }
            if (current instanceof OpenAIServiceException serviceFailure) {
                int status = serviceFailure.statusCode();
                if (status == 408 || status == 409 || status == 429 || status >= 500) {
                    return new ModelClientException(status == 429 ? FailureCategory.MODEL_RATE_LIMIT
                            : FailureCategory.MODEL_SERVER, true);
                }
                if (status == 401 || status == 403) {
                    return new ModelClientException(FailureCategory.MODEL_AUTHENTICATION, false);
                }
                if (status >= 400) {
                    return new ModelClientException(FailureCategory.MODEL_BAD_REQUEST, false);
                }
            }
        }
        return new ModelClientException(FailureCategory.MODEL_UNKNOWN, false);
    }

    @Override
    public void close() {
        // 避免 HTTP 连接池和 SDK 后台资源随应用生命周期一起泄漏。
        client.close();
    }

    /** 将内部角色映射为 SDK 对应的消息类型。 */
    private static ChatCompletionMessageParam toSdkMessage(Message message) {
        return switch (message.role()) {
            case SYSTEM -> ChatCompletionMessageParam.ofSystem(
                    ChatCompletionSystemMessageParam.builder().content(message.content()).build());
            case USER -> ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder().content(message.content()).build());
            case ASSISTANT -> {
                ChatCompletionAssistantMessageParam.Builder builder = ChatCompletionAssistantMessageParam.builder();
                if (!message.content().isBlank()) builder.content(message.content());
                for (ToolCall call : message.toolCalls()) {
                    var function = ChatCompletionMessageFunctionToolCall.Function.builder()
                            .name(call.name()).arguments(call.arguments().toString()).build();
                    builder.addToolCall(ChatCompletionMessageFunctionToolCall.builder()
                            .id(call.id()).type(JsonValue.from("function")).function(function).build());
                }
                yield ChatCompletionMessageParam.ofAssistant(builder.build());
            }
            case TOOL -> ChatCompletionMessageParam.ofTool(
                    ChatCompletionToolMessageParam.builder()
                            .toolCallId(message.toolCallId()).content(message.content()).build());
        };
    }

    /** 将 JSON Schema 包装成 OpenAI 兼容 function tool 定义。 */
    private static ChatCompletionTool toSdkTool(ToolDefinition definition) {
        try {
            JsonNode schemaNode = definition.inputSchema();
            java.util.Map<String, com.openai.core.JsonValue> schema = new java.util.LinkedHashMap<>();
            schemaNode.fields().forEachRemaining(entry ->
                    schema.put(entry.getKey(), com.openai.core.JsonValue.fromJsonNode(entry.getValue())));
            FunctionParameters parameters = FunctionParameters.builder().additionalProperties(schema).build();
            FunctionDefinition function = FunctionDefinition.builder()
                    .name(definition.name()).description(definition.description()).parameters(parameters).build();
            return ChatCompletionTool.ofFunction(ChatCompletionFunctionTool.builder().function(function).build());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid JSON Schema for tool " + definition.name(), e);
        }
    }
}
