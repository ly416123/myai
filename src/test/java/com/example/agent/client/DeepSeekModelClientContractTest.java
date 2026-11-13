package com.example.agent.client;

import com.example.agent.model.Message;
import com.example.agent.model.ModelResponse;
import com.example.agent.model.ToolDefinition;
import com.example.agent.workflow.FailureCategory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekModelClientContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void rejectsProviderBaseUrlsThatCouldEmbedCredentialsOrQueryData() {
        assertThrows(IllegalArgumentException.class, () -> new DeepSeekModelClient(
                "test-only-key", "deepseek-flash", Duration.ofSeconds(1), "https://user:secret@example.com/v1"));
        assertThrows(IllegalArgumentException.class, () -> new DeepSeekModelClient(
                "test-only-key", "deepseek-flash", Duration.ofSeconds(1), "https://example.com/v1?token=secret"));
        assertThrows(IllegalArgumentException.class, () -> new DeepSeekModelClient(
                "test-only-key", "deepseek-flash", Duration.ofSeconds(1),
                "https://api.deepseek.com", "socks5://proxy.internal:1080"));
    }

    @Test
    void sendsProviderRequestThroughConfiguredHttpProxy() throws Exception {
        AtomicReference<String> proxyRequestLine = new AtomicReference<>();
        AtomicReference<Integer> providerRequests = new AtomicReference<>(0);
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/chat/completions", exchange -> {
            providerRequests.updateAndGet(count -> count + 1);
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        provider.start();

        try (ServerSocket proxy = new ServerSocket(0)) {
            Thread proxyProbe = Thread.ofVirtual().start(() -> {
                try (var socket = proxy.accept();
                     var input = new java.io.BufferedReader(new java.io.InputStreamReader(
                             socket.getInputStream(), StandardCharsets.US_ASCII))) {
                    proxyRequestLine.set(input.readLine());
                } catch (Exception ignored) {
                    // The client is expected to observe a closed test proxy after it receives the request line.
                }
            });
            proxy.setSoTimeout(2_000);

            try (var client = new DeepSeekModelClient("test-only-key", "deepseek-flash", Duration.ofSeconds(2),
                    "http://127.0.0.1:" + provider.getAddress().getPort(),
                    "http://127.0.0.1:" + proxy.getLocalPort())) {
                assertThrows(ModelClientException.class,
                        () -> client.complete(List.of(Message.user("hello")), List.of()));
            }

            proxyProbe.join(2_000);
            assertNotNull(proxyRequestLine.get());
            assertTrue(proxyRequestLine.get().startsWith("POST http://127.0.0.1:"));
            assertEquals(0, providerRequests.get(), "The client must not bypass the proxy");
        } finally {
            provider.stop(0);
        }
    }

    @Test
    void mapsRequestAndMultipleToolResponseWithoutLoggingCredential() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"id":"fake-response","object":"chat.completion","created":1,"model":"deepseek-flash",
                     "choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[
                       {"id":"call-1","type":"function","function":{"name":"calculator","arguments":"{\\"operation\\":\\"add\\",\\"a\\":2,\\"b\\":3}"}},
                       {"id":"call-2","type":"function","function":{"name":"weather","arguments":"{\\"city\\":\\"北京\\"}"}}
                     ]},"finish_reason":"tool_calls"}],
                     "usage":{"prompt_tokens":17,"completion_tokens":8,"total_tokens":25}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        server.start();

        try (var client = new DeepSeekModelClient("test-only-secret", "deepseek-flash", Duration.ofSeconds(3),
                "http://127.0.0.1:" + server.getAddress().getPort())) {
            var schema = JSON.readTree("""
                    {"type":"object","properties":{"city":{"type":"string"}}}
                    """);
            ModelResponse response = client.complete(List.of(Message.user("查天气")), List.of(
                    new ToolDefinition("weather", "weather tool", schema)));

            assertEquals("/chat/completions", requestPath.get());
            assertEquals("Bearer test-only-secret", authorization.get());
            assertEquals("deepseek-flash", JSON.readTree(requestBody.get()).path("model").asText());
            assertEquals(1, JSON.readTree(requestBody.get()).path("tools").size());
            assertEquals(2, response.toolCalls().size());
            assertEquals("call-1", response.toolCalls().getFirst().id());
            assertEquals("calculator", response.toolCalls().getFirst().name());
            assertEquals(17L, response.promptTokens());
            assertEquals(8L, response.completionTokens());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void classifiesAuthenticationRateLimitAndServerErrorsWithoutProviderBody() throws Exception {
        assertProviderFailure(401, FailureCategory.MODEL_AUTHENTICATION, false);
        assertProviderFailure(408, FailureCategory.MODEL_TIMEOUT, true);
        assertProviderFailure(429, FailureCategory.MODEL_RATE_LIMIT, true);
        assertProviderFailure(503, FailureCategory.MODEL_SERVER, true);
    }

    @Test
    void classifiesProviderTimeoutAsRetryableAndDoesNotExposeCredential() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestReceived.countDown();
            try {
                Thread.sleep(250);
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (var response = exchange.getResponseBody()) { response.write(body); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        try (var client = new DeepSeekModelClient("timeout-test-secret", "deepseek-flash", Duration.ofMillis(50),
                "http://127.0.0.1:" + server.getAddress().getPort())) {
            ModelClientException failure = assertThrows(ModelClientException.class,
                    () -> client.complete(List.of(Message.user("hello")), List.of()));
            assertTrue(requestReceived.await(1, TimeUnit.SECONDS));
            assertEquals(FailureCategory.MODEL_TIMEOUT, failure.category());
            assertTrue(failure.retryable());
            assertFalse(failure.getMessage().contains("timeout-test-secret"));
        } finally {
            server.stop(0);
        }
    }

    private static void assertProviderFailure(int status, FailureCategory category, boolean retryable) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] body = "provider-secret-error-body".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var response = exchange.getResponseBody()) { response.write(body); }
        });
        server.start();
        try (var client = new DeepSeekModelClient("error-test-secret", "deepseek-flash", Duration.ofSeconds(2),
                "http://127.0.0.1:" + server.getAddress().getPort())) {
            ModelClientException failure = assertThrows(ModelClientException.class,
                    () -> client.complete(List.of(Message.user("hello")), List.of()));
            assertEquals(category, failure.category(), "HTTP " + status);
            assertEquals(retryable, failure.retryable(), "HTTP " + status);
            assertFalse(failure.getMessage().contains("provider-secret-error-body"));
            assertFalse(failure.getMessage().contains("error-test-secret"));
        } finally {
            server.stop(0);
        }
    }
}
