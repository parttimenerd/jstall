package me.bechberger.jstall.util.llm;

import me.bechberger.jstall.util.json.JsonParser;
import me.bechberger.jstall.util.json.JsonPrinter;
import me.bechberger.jstall.util.json.JsonValue;
import me.bechberger.jstall.util.json.JsonValue.JsonArray;
import me.bechberger.jstall.util.json.JsonValue.JsonBoolean;
import me.bechberger.jstall.util.json.JsonValue.JsonObject;
import me.bechberger.jstall.util.json.JsonValue.JsonString;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * LLM provider implementation for Ollama (local models) that calls Ollama's HTTP API directly.
 */
public class OllamaLlmProvider implements LlmProvider {

    private final URI baseUri;
    private final HttpClient httpClient;
    private final AiConfig.OllamaThinkMode defaultThinkMode;

    public OllamaLlmProvider(String host) {
        this(host, null);
    }

    public OllamaLlmProvider(String host, AiConfig.OllamaThinkMode defaultThinkMode) {
        this.baseUri = URI.create(host.endsWith("/") ? host.substring(0, host.length() - 1) : host);
        this.defaultThinkMode = defaultThinkMode;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public String getAdditionalInstructions() {
        return "Only mention deadlocks if they are explicitly mentioned in the thread dump analysis.";
    }

    @Override
    public String chat(String model, List<LlmProvider.Message> messages, StreamHandlers handlers)
            throws IOException, LlmProvider.LlmException {

        JsonObject body = buildChatRequestBody(model, messages, true, resolveThinkMode(model, handlers));
        HttpRequest request = HttpRequest.newBuilder()
            .uri(baseUri.resolve("/api/chat"))
            .timeout(Duration.ofMinutes(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JsonPrinter.printCompact(body)))
            .build();

        try {
            HttpResponse<java.io.InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                throw toLlmException(response.statusCode(), readAll(response.body()));
            }

            StringBuilder fullResponse = new StringBuilder();

            // Ollama streaming responses are line-delimited JSON objects.
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }

                    JsonValue chunk;
                    try {
                        chunk = JsonParser.parse(line);
                    } catch (JsonParser.JsonParseException e) {
                        // Ignore malformed chunks rather than aborting the whole request
                        continue;
                    }

                    JsonObject obj = chunk.asObject();

                    // done flag is per-chunk
                    boolean done = obj.has("done") && obj.get("done").isBoolean() && obj.get("done").asBoolean();

                    String token = extractMessageContent(obj);
                    if (token != null && !token.isEmpty()) {
                        // Best-effort separation of think / response. Ollama emits text inside <think>...</think>
                        // For simplicity: if a thinkingHandler is present and we see a <think> tag, route tokens to thinking.
                        if (handlers.thinkingHandler() != null) {
                            routeTokenWithThinkSupport(token, handlers, fullResponse);
                        } else {
                            fullResponse.append(token);
                            if (handlers.responseHandler() != null) {
                                handlers.responseHandler().accept(token);
                            }
                        }
                    }

                    if (done) {
                        break;
                    }
                }
            }

            return fullResponse.toString();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Ollama request interrupted", e);
        }
    }

    @Override
    public String getRawResponse(String model, List<LlmProvider.Message> messages)
            throws IOException, LlmProvider.LlmException {

        JsonObject body = buildChatRequestBody(model, messages, false, resolveThinkMode(model, null));
        HttpRequest request = HttpRequest.newBuilder()
            .uri(baseUri.resolve("/api/chat"))
            .timeout(Duration.ofMinutes(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JsonPrinter.printCompact(body)))
            .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw toLlmException(response.statusCode(), response.body());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Ollama request interrupted", e);
        }
    }

    private AiConfig.OllamaThinkMode resolveThinkMode(String model, StreamHandlers handlers) {
        // If caller wants thinking tokens, thinking must be enabled.
        boolean wantsThinking = handlers != null && handlers.thinkingHandler() != null;

        AiConfig.OllamaThinkMode configured = defaultThinkMode;
        if (configured == null) {
            // If not injected, keep backwards-compatible behavior:
            // - gpt-oss*: HIGH
            // - others: OFF unless thinking requested
            if (model != null && model.toLowerCase().startsWith("gpt-oss")) {
                configured = AiConfig.OllamaThinkMode.HIGH;
            } else {
                configured = AiConfig.OllamaThinkMode.OFF;
            }
        }

        if (wantsThinking && configured == AiConfig.OllamaThinkMode.OFF) {
            // Enable thinking if caller asked for it.
            return AiConfig.OllamaThinkMode.HIGH; // for non-gpt-oss bool think=true is fine
        }

        return configured;
    }

    private JsonObject buildChatRequestBody(String model,
                                           List<LlmProvider.Message> messages,
                                           boolean stream,
                                           AiConfig.OllamaThinkMode thinkMode) {

        JsonArray msgs = new JsonArray();
        for (LlmProvider.Message m : messages) {
            msgs = msgs.add(new JsonObject()
                .put("role", new JsonString(m.role()))
                .put("content", new JsonString(m.content())));
        }

        JsonObject root = new JsonObject()
            .put("model", new JsonString(model))
            .put("messages", msgs)
            .put("stream", new JsonBoolean(stream));

        // Think handling:
        // - gpt-oss requires string think (low/medium/high)
        // - other models often accept boolean
        if (thinkMode != null) {
            if (model != null && model.toLowerCase().startsWith("gpt-oss")) {
                if (thinkMode != AiConfig.OllamaThinkMode.OFF) {
                    root = root.put("think", new JsonString(thinkMode.name().toLowerCase()));
                }
            } else {
                root = root.put("think", new JsonBoolean(thinkMode != AiConfig.OllamaThinkMode.OFF));
            }
        }

        return root;
    }

    private String extractMessageContent(JsonObject chunkObj) {
        if (!chunkObj.has("message") || !chunkObj.get("message").isObject()) {
            return null;
        }
        JsonObject msg = chunkObj.get("message").asObject();
        if (!msg.has("content") || !msg.get("content").isString()) {
            return null;
        }
        return msg.get("content").asString();
    }

    private void routeTokenWithThinkSupport(String token, StreamHandlers handlers, StringBuilder fullResponse) {
        // Very small state machine would be ideal, but keep it simple:
        // - content between <think> and </think> goes to thinkingHandler
        // - everything else goes to responseHandler and is appended to fullResponse
        // This is robust enough for typical thinking outputs.

        // We do not keep state across chunks here; this means tokens may be mis-routed if tags split.
        // But this matches "keep it simple".
        if (token.contains("<think>") || token.contains("</think>")) {
            if (handlers.thinkingHandler() != null) {
                handlers.thinkingHandler().accept(token);
            }
            return;
        }

        // If it looks like thinking output and handler is present, send it there. Otherwise treat it as response.
        if (handlers.thinkingHandler() != null && token.startsWith("[THINK]") ) {
            handlers.thinkingHandler().accept(token);
            return;
        }

        fullResponse.append(token);
        if (handlers.responseHandler() != null) {
            handlers.responseHandler().accept(token);
        }
    }

    private LlmProvider.LlmException toLlmException(int statusCode, String body) {
        String msg = body;
        // Best effort: Ollama error responses are often JSON like {"error":"..."}
        try {
            JsonValue parsed = JsonParser.parse(body);
            if (parsed.isObject()) {
                JsonObject obj = parsed.asObject();
                if (obj.has("error") && obj.get("error").isString()) {
                    msg = obj.get("error").asString();
                }
            }
        } catch (Exception ignored) {
        }
        return new LlmProvider.LlmException("Ollama error: " + msg, statusCode);
    }

    private static String readAll(java.io.InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
}