package me.bechberger.jstall.util.llm;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiLlmProviderTest {

    @Test
    void testStreamingChat() throws Exception {
        // Set up a fake OpenAI-compatible server
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/v1/chat/completions", exchange -> {
            String sseResponse =
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\n" +
                "data: [DONE]\n\n";

            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            byte[] body = sseResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        try {
            OpenAiLlmProvider provider = new OpenAiLlmProvider("http://127.0.0.1:" + port);

            StringBuilder collected = new StringBuilder();
            String result = provider.chat("test-model",
                List.of(new LlmProvider.Message("user", "hi")),
                new LlmProvider.StreamHandlers(collected::append));

            assertThat(result).isEqualTo("Hello world");
            assertThat(collected.toString()).isEqualTo("Hello world");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testStreamingChatWithReasoningContent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/v1/chat/completions", exchange -> {
            String sseResponse =
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking...\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"Answer\"}}]}\n\n" +
                "data: [DONE]\n\n";

            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            byte[] body = sseResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        try {
            OpenAiLlmProvider provider = new OpenAiLlmProvider("http://127.0.0.1:" + port);

            StringBuilder responseTokens = new StringBuilder();
            StringBuilder thinkingTokens = new StringBuilder();
            String result = provider.chat("test-model",
                List.of(new LlmProvider.Message("user", "think about this")),
                new LlmProvider.StreamHandlers(responseTokens::append, thinkingTokens::append));

            assertThat(result).isEqualTo("Answer");
            assertThat(thinkingTokens.toString()).isEqualTo("thinking...");
            assertThat(responseTokens.toString()).isEqualTo("Answer");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testNonStreamingRawResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        String jsonResponse = "{\"choices\":[{\"message\":{\"content\":\"response\"}}]}";

        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] body = jsonResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        try {
            OpenAiLlmProvider provider = new OpenAiLlmProvider("http://127.0.0.1:" + port);
            String result = provider.getRawResponse("test-model",
                List.of(new LlmProvider.Message("user", "hi")));

            assertThat(result).isEqualTo(jsonResponse);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testErrorResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/v1/chat/completions", exchange -> {
            String errorBody = "{\"error\":{\"message\":\"model not found\"}}";
            byte[] body = errorBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        try {
            OpenAiLlmProvider provider = new OpenAiLlmProvider("http://127.0.0.1:" + port);

            assertThatThrownBy(() -> provider.chat("bad-model",
                List.of(new LlmProvider.Message("user", "hi")),
                new LlmProvider.StreamHandlers(s -> {})))
                .isInstanceOf(LlmProvider.LlmException.class)
                .hasMessageContaining("model not found");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testSupportsStreaming() {
        OpenAiLlmProvider provider = new OpenAiLlmProvider("http://localhost:8080");
        assertThat(provider.supportsStreaming()).isTrue();
    }

    @Test
    void testMalformedChunksAreSkipped() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/v1/chat/completions", exchange -> {
            String sseResponse =
                "data: not valid json\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n" +
                "data: [DONE]\n\n";

            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            byte[] body = sseResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        try {
            OpenAiLlmProvider provider = new OpenAiLlmProvider("http://127.0.0.1:" + port);
            String result = provider.chat("test-model",
                List.of(new LlmProvider.Message("user", "hi")),
                new LlmProvider.StreamHandlers(s -> {}));

            assertThat(result).isEqualTo("ok");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testAuthError401() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/v1/chat/completions", exchange -> {
            String errorBody = "{\"error\":{\"message\":\"Unauthorized\"}}";
            byte[] body = errorBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        try {
            OpenAiLlmProvider provider = new OpenAiLlmProvider("http://127.0.0.1:" + port);

            assertThatThrownBy(() -> provider.chat("test-model",
                List.of(new LlmProvider.Message("user", "hi")),
                new LlmProvider.StreamHandlers(s -> {})))
                .isInstanceOf(LlmProvider.LlmException.class)
                .satisfies(e -> {
                    LlmProvider.LlmException llmEx = (LlmProvider.LlmException) e;
                    assertThat(llmEx.isAuthError()).isTrue();
                });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testChatWithToolsParsesToolCalls() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/v1/chat/completions", exchange -> {
            String responseBody = """
                {"choices": [{"message": {"role": "assistant", "content": null,
                  "tool_calls": [{"id": "call_1", "type": "function",
                    "function": {"name": "get_lock_info", "arguments": "{}"}}]}}]}
                """;
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        try {
            OpenAiLlmProvider provider = new OpenAiLlmProvider("http://127.0.0.1:" + port);
            OpenAiLlmProvider.ChatResponse response = provider.chatWithTools("test-model",
                List.of(new LlmProvider.Message("user", "analyze")),
                List.of());

            assertThat(response.hasToolCalls()).isTrue();
            assertThat(response.toolCalls()).hasSize(1);
            assertThat(response.toolCalls().get(0).name()).isEqualTo("get_lock_info");
            assertThat(response.toolCalls().get(0).id()).isEqualTo("call_1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testChatWithToolsNoToolCalls() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/v1/chat/completions", exchange -> {
            String responseBody = """
                {"choices": [{"message": {"role": "assistant",
                  "content": "Everything looks fine."}}]}
                """;
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        try {
            OpenAiLlmProvider provider = new OpenAiLlmProvider("http://127.0.0.1:" + port);
            OpenAiLlmProvider.ChatResponse response = provider.chatWithTools("test-model",
                List.of(new LlmProvider.Message("user", "analyze")),
                List.of());

            assertThat(response.hasToolCalls()).isFalse();
            assertThat(response.content()).isEqualTo("Everything looks fine.");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testChatWithToolsEmptyChoices() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/v1/chat/completions", exchange -> {
            String responseBody = "{\"choices\": []}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        try {
            OpenAiLlmProvider provider = new OpenAiLlmProvider("http://127.0.0.1:" + port);
            OpenAiLlmProvider.ChatResponse response = provider.chatWithTools("test-model",
                List.of(new LlmProvider.Message("user", "analyze")),
                List.of());

            assertThat(response.hasToolCalls()).isFalse();
            assertThat(response.content()).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testErrorResponsePlainStringError() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/v1/chat/completions", exchange -> {
            // Some servers return error as a plain string
            String errorBody = "{\"error\":\"rate limit exceeded\"}";
            byte[] body = errorBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        try {
            OpenAiLlmProvider provider = new OpenAiLlmProvider("http://127.0.0.1:" + port);

            assertThatThrownBy(() -> provider.chat("test-model",
                List.of(new LlmProvider.Message("user", "hi")),
                new LlmProvider.StreamHandlers(s -> {})))
                .isInstanceOf(LlmProvider.LlmException.class)
                .hasMessageContaining("rate limit exceeded");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testTrailingSlashInHostUrl() {
        // Should not cause double-slash in URL
        OpenAiLlmProvider provider = new OpenAiLlmProvider("http://localhost:8080/");
        assertThat(provider.supportsStreaming()).isTrue();
    }

    @Test
    void testGetAdditionalInstructions() {
        OpenAiLlmProvider provider = new OpenAiLlmProvider("http://localhost:8080");
        assertThat(provider.getAdditionalInstructions()).contains("deadlock");
    }

    @Test
    void testStreamingWithEmptyDeltaContent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/v1/chat/completions", exchange -> {
            // Include empty content deltas and role-only deltas (common at stream start)
            String sseResponse =
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"Result\"}}]}\n\n" +
                "data: [DONE]\n\n";

            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            byte[] body = sseResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        try {
            OpenAiLlmProvider provider = new OpenAiLlmProvider("http://127.0.0.1:" + port);
            StringBuilder collected = new StringBuilder();
            String result = provider.chat("test-model",
                List.of(new LlmProvider.Message("user", "hi")),
                new LlmProvider.StreamHandlers(collected::append));

            assertThat(result).isEqualTo("Result");
            assertThat(collected.toString()).isEqualTo("Result");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testChatWithToolsArgumentsParsedFromString() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/v1/chat/completions", exchange -> {
            // Arguments as JSON string (most common format from OpenAI)
            String responseBody = """
                {"choices": [{"message": {"role": "assistant", "content": null,
                  "tool_calls": [{"id": "call_1", "type": "function",
                    "function": {"name": "search_stack_frames",
                      "arguments": "{\\"pattern\\": \\"com.example\\"}"}}]}}]}
                """;
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        try {
            OpenAiLlmProvider provider = new OpenAiLlmProvider("http://127.0.0.1:" + port);
            OpenAiLlmProvider.ChatResponse response = provider.chatWithTools("test-model",
                List.of(new LlmProvider.Message("user", "analyze")),
                List.of());

            assertThat(response.toolCalls()).hasSize(1);
            ToolCall call = response.toolCalls().get(0);
            assertThat(call.getString("pattern")).isEqualTo("com.example");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testRawResponseErrorHandling() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/v1/chat/completions", exchange -> {
            String errorBody = "{\"error\":{\"message\":\"server overloaded\"}}";
            byte[] body = errorBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        try {
            OpenAiLlmProvider provider = new OpenAiLlmProvider("http://127.0.0.1:" + port);

            assertThatThrownBy(() -> provider.getRawResponse("test-model",
                List.of(new LlmProvider.Message("user", "hi"))))
                .isInstanceOf(LlmProvider.LlmException.class)
                .hasMessageContaining("server overloaded");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testRetryOn429() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        AtomicInteger requestCount = new AtomicInteger(0);

        server.createContext("/v1/chat/completions", exchange -> {
            int count = requestCount.incrementAndGet();
            if (count <= 2) {
                // First 2 requests: rate limited
                String errorBody = "{\"error\":{\"message\":\"rate limited\"}}";
                byte[] body = errorBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(429, body.length);
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().close();
            } else {
                // Third request succeeds
                String sseResponse =
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Success after retry\"}}]}\n\n" +
                    "data: [DONE]\n\n";
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                byte[] body = sseResponse.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().close();
            }
        });
        server.start();

        try {
            OpenAiLlmProvider provider = new OpenAiLlmProvider("http://127.0.0.1:" + port);
            String result = provider.chat("test-model",
                List.of(new LlmProvider.Message("user", "hi")),
                new LlmProvider.StreamHandlers(s -> {}));

            assertThat(result).isEqualTo("Success after retry");
            assertThat(requestCount.get()).isEqualTo(3); // 2 retries + 1 success
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testRetryExhaustedThrows() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        AtomicInteger requestCount = new AtomicInteger(0);

        server.createContext("/v1/chat/completions", exchange -> {
            requestCount.incrementAndGet();
            // Always return 503
            String errorBody = "{\"error\":{\"message\":\"service unavailable\"}}";
            byte[] body = errorBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        try {
            OpenAiLlmProvider provider = new OpenAiLlmProvider("http://127.0.0.1:" + port);

            assertThatThrownBy(() -> provider.chat("test-model",
                List.of(new LlmProvider.Message("user", "hi")),
                new LlmProvider.StreamHandlers(s -> {})))
                .isInstanceOf(LlmProvider.LlmException.class)
                .hasMessageContaining("service unavailable");

            // Should have tried 1 + 3 retries = 4 times
            assertThat(requestCount.get()).isEqualTo(4);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testNoRetryOnClientError() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        AtomicInteger requestCount = new AtomicInteger(0);

        server.createContext("/v1/chat/completions", exchange -> {
            requestCount.incrementAndGet();
            String errorBody = "{\"error\":{\"message\":\"bad request\"}}";
            byte[] body = errorBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        try {
            OpenAiLlmProvider provider = new OpenAiLlmProvider("http://127.0.0.1:" + port);

            assertThatThrownBy(() -> provider.chat("test-model",
                List.of(new LlmProvider.Message("user", "hi")),
                new LlmProvider.StreamHandlers(s -> {})))
                .isInstanceOf(LlmProvider.LlmException.class)
                .hasMessageContaining("bad request");

            // Should NOT retry on 400 — only 1 request
            assertThat(requestCount.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testChatWithToolsStreamingAccumulatesToolCalls() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/v1/chat/completions", exchange -> {
            // SSE stream: a content token before a streamed tool call
            String sseResponse =
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":null}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"Checking lock contention...\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\"," +
                    "\"type\":\"function\",\"function\":{\"name\":\"get_lock_info\",\"arguments\":\"\"}}]}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0," +
                    "\"function\":{\"arguments\":\"{}\"}}]}}]}\n\n" +
                "data: {\"choices\":[{\"finish_reason\":\"tool_calls\"}]}\n\n" +
                "data: [DONE]\n\n";
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            byte[] body = sseResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        try {
            OpenAiLlmProvider provider = new OpenAiLlmProvider("http://127.0.0.1:" + port);
            StringBuilder verbose = new StringBuilder();

            OpenAiLlmProvider.ChatResponse response = provider.chatWithToolsStreaming(
                "test-model",
                List.of(new LlmProvider.Message("user", "analyze")),
                List.of(),
                verbose::append);

            assertThat(response.hasToolCalls()).isTrue();
            assertThat(response.toolCalls()).hasSize(1);
            assertThat(response.toolCalls().get(0).name()).isEqualTo("get_lock_info");
            assertThat(response.toolCalls().get(0).id()).isEqualTo("call_1");
            assertThat(verbose.toString()).isEqualTo("Checking lock contention...");
        } finally {
            server.stop(0);
        }
    }
}
