package me.bechberger.jstall.util.llm;

import me.bechberger.util.json.JSONParser;
import me.bechberger.util.json.PrettyPrinter;
import me.bechberger.util.json.Util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LLM provider implementation using the OpenAI-compatible chat completions API.
 *
 * <p>Works with any server exposing {@code /v1/chat/completions} — llama-server (llama.cpp),
 * Ollama, vLLM, text-generation-inference, LocalAI, etc.
 *
 * <p>Automatically retries on transient errors (429 Too Many Requests, 503 Service Unavailable)
 * with exponential backoff.
 */
public class OpenAiLlmProvider implements LlmProvider {

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 1000;

    private final URI baseUri;
    private final HttpClient httpClient;

    public OpenAiLlmProvider(String host) {
        this.baseUri = URI.create(host.endsWith("/") ? host.substring(0, host.length() - 1) : host);
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

        Map<String, Object> body = buildRequestBody(model, messages, true);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(baseUri.resolve("/v1/chat/completions"))
            .timeout(Duration.ofMinutes(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(PrettyPrinter.compactPrint(body)))
            .build();

        HttpResponse<java.io.InputStream> response = sendWithRetry(request,
            HttpResponse.BodyHandlers.ofInputStream());

        StringBuilder fullResponse = new StringBuilder();

        // OpenAI streaming: SSE lines "data: {json}" terminated by "data: [DONE]"
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (!line.startsWith("data: ")) {
                        continue;
                    }
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }

                    Object parsed;
                    try {
                        parsed = JSONParser.parse(data);
                    } catch (Exception e) {
                        continue;
                    }

                    Map<String, Object> obj = Util.asMap(parsed);
                    String token = extractDeltaContent(obj);
                    String thinkingToken = extractDeltaReasoningContent(obj);

                    if (thinkingToken != null && !thinkingToken.isEmpty()
                            && handlers.thinkingHandler() != null) {
                        handlers.thinkingHandler().accept(thinkingToken);
                    }

                    if (token != null && !token.isEmpty()) {
                        fullResponse.append(token);
                        if (handlers.responseHandler() != null) {
                            handlers.responseHandler().accept(token);
                        }
                    }
                }
            }

            return fullResponse.toString();
    }

    @Override
    public String getRawResponse(String model, List<LlmProvider.Message> messages)
            throws IOException, LlmProvider.LlmException {

        Map<String, Object> body = buildRequestBody(model, messages, false);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(baseUri.resolve("/v1/chat/completions"))
            .timeout(Duration.ofMinutes(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(PrettyPrinter.compactPrint(body)))
            .build();

        HttpResponse<String> response = sendWithRetry(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private Map<String, Object> buildRequestBody(String model,
                                                  List<LlmProvider.Message> messages,
                                                  boolean stream) {
        return buildRequestBody(model, messages, stream, null);
    }

    private Map<String, Object> buildRequestBody(String model,
                                                  List<LlmProvider.Message> messages,
                                                  boolean stream,
                                                  List<ToolDefinition> tools) {
        List<Object> msgs = new ArrayList<>();
        for (LlmProvider.Message m : messages) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", m.role());
            if (m.content() != null) {
                msg.put("content", m.content());
            }
            if (m.toolCallId() != null) {
                msg.put("tool_call_id", m.toolCallId());
            }
            if (m.toolCalls() != null) {
                msg.put("tool_calls", m.toolCalls());
            }
            msgs.add(msg);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model", model);
        root.put("messages", msgs);
        root.put("stream", stream);

        if (tools != null && !tools.isEmpty()) {
            List<Object> toolSchemas = new ArrayList<>();
            for (ToolDefinition tool : tools) {
                toolSchemas.add(tool.toOpenAiSchema());
            }
            root.put("tools", toolSchemas);
        }

        return root;
    }

    /**
     * Sends a chat request with tool definitions (non-streaming).
     * Returns the parsed response including any tool calls.
     *
     * @return A ChatResponse containing either content or tool calls
     */
    public ChatResponse chatWithTools(String model, List<LlmProvider.Message> messages,
                                       List<ToolDefinition> tools)
            throws IOException, LlmProvider.LlmException {

        Map<String, Object> body = buildRequestBody(model, messages, false, tools);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(baseUri.resolve("/v1/chat/completions"))
            .timeout(Duration.ofMinutes(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(PrettyPrinter.compactPrint(body)))
            .build();

        HttpResponse<String> response = sendWithRetry(request, HttpResponse.BodyHandlers.ofString());
        return parseChatResponse(response.body());
    }

    /**
     * Parsed response from a chat-with-tools request.
     */
    public record ChatResponse(String content, List<ToolCall> toolCalls, Object rawToolCalls) {

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    @SuppressWarnings("unchecked")
    private ChatResponse parseChatResponse(String responseBody) throws IOException {
        Map<String, Object> root = Util.asMap(JSONParser.parse(responseBody));
        List<?> choices = (List<?>) root.get("choices");
        if (choices == null || choices.isEmpty()) {
            return new ChatResponse("", List.of(), null);
        }

        Map<String, Object> choice = Util.asMap(choices.get(0));
        Map<String, Object> message = Util.asMap(choice.get("message"));
        if (message == null) {
            return new ChatResponse("", List.of(), null);
        }

        String content = message.get("content") instanceof String s ? s : null;

        List<ToolCall> toolCalls = new ArrayList<>();
        Object rawToolCalls = message.get("tool_calls");
        if (rawToolCalls instanceof List<?> tcList) {
            for (Object tcObj : tcList) {
                Map<String, Object> tc = Util.asMap(tcObj);
                String id = tc.get("id") instanceof String s ? s : "call_" + toolCalls.size();
                Map<String, Object> function = Util.asMap(tc.get("function"));
                if (function == null) continue;

                String name = function.get("name") instanceof String s ? s : "";
                Map<String, Object> args = Map.of();
                if (function.get("arguments") instanceof String argsStr) {
                    try {
                        Object parsed = JSONParser.parse(argsStr);
                        if (parsed instanceof Map<?, ?> m) {
                            args = Util.asMap(parsed);
                        }
                    } catch (Exception ignored) {
                    }
                } else if (function.get("arguments") instanceof Map<?, ?>) {
                    args = Util.asMap(function.get("arguments"));
                }
                toolCalls.add(new ToolCall(id, name, args));
            }
        }

        return new ChatResponse(content, toolCalls, rawToolCalls);
    }

    @SuppressWarnings("unchecked")
    private String extractDeltaContent(Map<String, Object> obj) {
        if (!(obj.get("choices") instanceof List<?> choices) || choices.isEmpty()) {
            return null;
        }
        Map<String, Object> choice = Util.asMap(choices.get(0));
        if (!(choice.get("delta") instanceof Map<?, ?>)) {
            return null;
        }
        Map<String, Object> delta = Util.asMap(choice.get("delta"));
        if (delta.get("content") instanceof String content) {
            return content;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String extractDeltaReasoningContent(Map<String, Object> obj) {
        if (!(obj.get("choices") instanceof List<?> choices) || choices.isEmpty()) {
            return null;
        }
        Map<String, Object> choice = Util.asMap(choices.get(0));
        if (!(choice.get("delta") instanceof Map<?, ?>)) {
            return null;
        }
        Map<String, Object> delta = Util.asMap(choice.get("delta"));
        // Some servers use "reasoning_content" for thinking/reasoning tokens
        if (delta.get("reasoning_content") instanceof String reasoning) {
            return reasoning;
        }
        return null;
    }

    private LlmProvider.LlmException toLlmException(int statusCode, String body) {
        String msg = body;
        try {
            Map<String, Object> obj = Util.asMap(JSONParser.parse(body));
            if (obj.get("error") instanceof Map<?, ?>) {
                Map<String, Object> error = Util.asMap(obj.get("error"));
                if (error.get("message") instanceof String errorMsg) {
                    msg = errorMsg;
                }
            } else if (obj.get("error") instanceof String error) {
                msg = error;
            }
        } catch (Exception ignored) {
        }
        return new LlmProvider.LlmException("OpenAI API error: " + msg, statusCode);
    }

    private static boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode == 503 || statusCode == 502;
    }

    /**
     * Sends an HTTP request with retry logic for transient errors (429, 502, 503).
     * Uses exponential backoff between retries.
     */
    private <T> HttpResponse<T> sendWithRetry(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, LlmProvider.LlmException {
        LlmProvider.LlmException lastException = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpResponse<T> response = httpClient.send(request, handler);
                if (response.statusCode() < 400) {
                    return response;
                }
                // Read error body
                String errorBody;
                Object responseBody = response.body();
                if (responseBody instanceof java.io.InputStream is) {
                    errorBody = readAll(is);
                } else {
                    errorBody = responseBody.toString();
                }

                lastException = toLlmException(response.statusCode(), errorBody);

                if (!isRetryable(response.statusCode()) || attempt == MAX_RETRIES) {
                    throw lastException;
                }

                // Backoff before retry
                long backoff = INITIAL_BACKOFF_MS * (1L << attempt);
                System.err.println("  [retry " + (attempt + 1) + "/" + MAX_RETRIES + "] "
                    + response.statusCode() + " — retrying in " + backoff + "ms");
                Thread.sleep(backoff);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Request interrupted", e);
            }
        }
        throw lastException != null ? lastException
            : new LlmProvider.LlmException("Request failed after retries", 500);
    }

    private static String readAll(java.io.InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Runs a full tool-calling conversation loop: sends the initial request with tools,
     * executes any tool calls, sends tool results back, and repeats until the model
     * produces a final text response (or max iterations reached).
     *
     * <p>The final response is streamed via handlers for real-time output.
     *
     * @param model The model to use
     * @param messages The initial messages
     * @param tools The tool definitions
     * @param executor The tool executor
     * @param handlers Stream handlers for the final response
     * @param maxIterations Maximum number of tool-call rounds (safety limit)
     * @return The final text response
     */
    public String chatWithToolLoop(String model, List<LlmProvider.Message> messages,
                                    List<ToolDefinition> tools, ToolExecutor executor,
                                    StreamHandlers handlers, int maxIterations)
            throws IOException, LlmProvider.LlmException {

        List<LlmProvider.Message> conversation = new ArrayList<>(messages);

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            ChatResponse response = chatWithTools(model, conversation, tools);

            if (!response.hasToolCalls()) {
                // Final response — stream it to handlers
                String content = response.content() != null ? response.content() : "";
                if (handlers != null && handlers.responseHandler() != null && !content.isEmpty()) {
                    handlers.responseHandler().accept(content);
                }
                return content;
            }

            // Model wants to call tools
            conversation.add(LlmProvider.Message.assistantWithToolCalls(response.rawToolCalls()));

            // Execute each tool call and add results
            for (ToolCall call : response.toolCalls()) {
                System.err.println("  [tool " + (iteration + 1) + "/" + maxIterations + "] "
                    + call.name() + "(" + formatArgs(call.arguments()) + ")");
                String result = executor.execute(call);
                if (result.length() > 8000) {
                    result = result.substring(0, 8000) + "\n... (truncated to 8000 chars)";
                }
                conversation.add(LlmProvider.Message.toolResult(call.id(), result));
            }
        }

        // Exceeded max iterations — stream the final response without tools
        System.err.println("  [tools] Generating final answer...");
        return chat(model, conversation, handlers);
    }

    private static String formatArgs(Map<String, Object> args) {
        if (args.isEmpty()) return "";
        return args.entrySet().stream()
            .map(e -> e.getKey() + "=" + truncateArg(String.valueOf(e.getValue())))
            .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String truncateArg(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }
}
