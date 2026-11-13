package com.example.agent;

import com.example.agent.client.DeepSeekModelClient;
import com.example.agent.model.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AgentConfigurationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsProviderCredentialFromEnvironmentConfiguredSecretFile() throws Exception {
        Path mountedCredential = temporaryDirectory.resolve("deepseek-api-key");
        Files.writeString(mountedCredential, "test-only-not-a-real-key\n");
        var properties = new AgentProperties();
        properties.setApiKeyFile(temporaryDirectory.resolve("unused-default-file").toString());
        var environment = new MockEnvironment()
                .withProperty("DEEPSEEK_KEY_FILE", mountedCredential.toString());

        try (var modelClient = new AgentConfiguration().modelClient(properties, environment)) {
            assertInstanceOf(DeepSeekModelClient.class, modelClient);
        }
    }

    @Test
    void modelClientUsesConfiguredProviderBaseUrl() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            byte[] response = """
                    {"id":"stub","object":"chat.completion","created":1,"model":"deepseek-flash",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"pong"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        server.start();
        var properties = new AgentProperties();
        properties.setModelTimeout(Duration.ofSeconds(3));
        var environment = new MockEnvironment()
                .withProperty("DEEPSEEK_API_KEY", "test-only-not-a-real-key")
                .withProperty("DEEPSEEK_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");

        try (var modelClient = new AgentConfiguration().modelClient(properties, environment)) {
            assertEquals("pong", modelClient.complete(List.of(Message.user("ping")), List.of()).content());
            assertEquals("/v1/chat/completions", requestPath.get());
        } finally {
            server.stop(0);
        }
    }
}
