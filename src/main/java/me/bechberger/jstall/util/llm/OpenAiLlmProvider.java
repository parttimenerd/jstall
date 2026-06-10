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
import java.util.function.Consumer;
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
    private boolean enableThinking = false;

    public OpenAiLlmProvider(String host) {
        this.baseUri = URI.create(host.endsWith("/") ? host.substring(0, host.length() - 1) : host);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    /**
     * When true, requests the model to emit chain-of-thought reasoning tokens.
     * When false (default), injects chat_template_kwargs={"enable_thinking":false}
     * to suppress reasoning on models like Qwen3 that support it, cutting response
     * time roughly in half.
     */
    public void setEnableThinking(boolean enableThinking) {
        this.enableThinking = enableThinking;
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
        // Cap output length for local models to bound generation time.
        // 600 tokens ≈ ~450 words, enough for a thorough analysis.
        root.put("max_tokens", 600);

        if (tools != null && !tools.isEmpty()) {
            List<Object> toolSchemas = new ArrayList<>();
            for (ToolDefinition tool : tools) {
                toolSchemas.add(tool.toOpenAiSchema());
            }
            root.put("tools", toolSchemas);
        }

        // Suppress chain-of-thought on Qwen3-family models unless --think is set.
        // chat_template_kwargs is a llama-server extension; other OpenAI-compatible
        // servers ignore unknown fields, so this is safe to send unconditionally.
        root.put("chat_template_kwargs", Map.of("enable_thinking", enableThinking));

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

    /**
     * Streaming variant of {@link #chatWithTools}: sends {@code stream:true} alongside tools.
     * Content delta tokens emitted before the tool-call decision (model "thinking out loud")
     * are forwarded to {@code verboseHandler} so the user sees progress instead of silence.
     * Accumulates tool-call JSON fragments from the SSE stream and returns a complete
     * {@link ChatResponse} once the stream ends.
     *
     * @param verboseHandler Optional consumer for pre-tool content tokens. Pass null to suppress.
     */
    public ChatResponse chatWithToolsStreaming(String model,
                                               List<LlmProvider.Message> messages,
                                               List<ToolDefinition> tools,
                                               Consumer<String> verboseHandler)
            throws IOException, LlmProvider.LlmException {

        Map<String, Object> body = buildRequestBody(model, messages, true, tools);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(baseUri.resolve("/v1/chat/completions"))
            .timeout(Duration.ofMinutes(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(PrettyPrinter.compactPrint(body)))
            .build();

        HttpResponse<java.io.InputStream> response = sendWithRetry(request,
            HttpResponse.BodyHandlers.ofInputStream());

        Map<Integer, String> tcIds   = new LinkedHashMap<>();
        Map<Integer, String> tcNames = new LinkedHashMap<>();
        Map<Integer, StringBuilder> tcArgs = new LinkedHashMap<>();
        StringBuilder contentBuf = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || !line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) break;
                Map<String, Object> obj;
                try { obj = Util.asMap(JSONParser.parse(data)); } catch (Exception e) { continue; }
                String token = extractDeltaContent(obj);
                if (token != null && !token.isEmpty()) {
                    contentBuf.append(token);
                }
                accumulateDeltaToolCalls(obj, tcIds, tcNames, tcArgs);
            }
        }

        if (tcNames.isEmpty()) {
            // Direct answer — caller's responseHandler will stream it; don't also send to verboseHandler
            return new ChatResponse(contentBuf.isEmpty() ? null : contentBuf.toString(), List.of(), null);
        }

        // Pre-tool reasoning content — send to verboseHandler before announcing tool calls
        if (verboseHandler != null && !contentBuf.isEmpty()) {
            verboseHandler.accept(contentBuf.toString());
            // Ensure next stderr line ([tool] ...) starts on a new line
            System.err.println();
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        List<Map<String, Object>> rawList = new ArrayList<>();
        for (Integer index : tcNames.keySet()) {
            String id       = tcIds.getOrDefault(index, "call_" + index);
            String name     = tcNames.get(index);
            String argsJson = tcArgs.containsKey(index) ? tcArgs.get(index).toString() : "{}";
            Map<String, Object> args = Map.of();
            try {
                Object parsed = JSONParser.parse(argsJson);
                if (parsed instanceof Map<?, ?>) args = Util.asMap(parsed);
            } catch (Exception ignored) {}
            toolCalls.add(new ToolCall(id, name, args));
            Map<String, Object> fnMap = new LinkedHashMap<>();
            fnMap.put("name", name);
            fnMap.put("arguments", argsJson);
            Map<String, Object> tcMap = new LinkedHashMap<>();
            tcMap.put("id", id);
            tcMap.put("type", "function");
            tcMap.put("function", fnMap);
            rawList.add(tcMap);
        }
        return new ChatResponse(contentBuf.isEmpty() ? null : contentBuf.toString(), toolCalls, rawList);
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

    @SuppressWarnings("unchecked")
    private void accumulateDeltaToolCalls(Map<String, Object> obj,
                                           Map<Integer, String> ids,
                                           Map<Integer, String> names,
                                           Map<Integer, StringBuilder> args) {
        if (!(obj.get("choices") instanceof List<?> choices) || choices.isEmpty()) return;
        Map<String, Object> choice = Util.asMap(choices.get(0));
        if (!(choice.get("delta") instanceof Map<?, ?>)) return;
        Map<String, Object> delta = Util.asMap(choice.get("delta"));
        if (!(delta.get("tool_calls") instanceof List<?> toolCallDeltas)) return;
        for (Object tcObj : toolCallDeltas) {
            Map<String, Object> tc = Util.asMap(tcObj);
            int index = tc.get("index") instanceof Number n ? n.intValue() : 0;
            if (tc.get("id") instanceof String id) ids.put(index, id);
            if (tc.get("function") instanceof Map<?, ?>) {
                Map<String, Object> fn = Util.asMap(tc.get("function"));
                if (fn.get("name") instanceof String n) names.put(index, n);
                if (fn.get("arguments") instanceof String frag)
                    args.computeIfAbsent(index, k -> new StringBuilder()).append(frag);
            }
        }
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
        String result = chatWithToolLoop(model, conversation, tools, executor, handlers, maxIterations, true);
        return result;
    }

    /**
     * Tool-calling loop that mutates the supplied {@code conversation} list in place,
     * so callers (e.g. the chat REPL) can maintain full history across turns.
     *
     * @param conversation Mutable message list; grows with assistant + tool messages.
     * @param appendFinalAssistant If true, appends the final assistant reply to the list.
     * @return The final text response
     */
    public String chatWithToolLoop(String model, List<LlmProvider.Message> conversation,
                                    List<ToolDefinition> tools, ToolExecutor executor,
                                    StreamHandlers handlers, int maxIterations,
                                    boolean appendFinalAssistant)
            throws IOException, LlmProvider.LlmException {
        return chatWithToolLoop(model, conversation, tools, executor, handlers, maxIterations,
            appendFinalAssistant, null);
    }

    /**
     * Tool-calling loop with optional verbose handler for streaming pre-tool reasoning tokens.
     *
     * <p>After the first iteration the tool definitions are omitted from subsequent requests —
     * the model already has the schemas in its assistant messages, so resending them wastes
     * ~2400 tokens per round. {@code buildRequestBody} already handles {@code tools=null}
     * correctly (does not emit the {@code "tools"} key).
     *
     * @param verboseHandler If non-null, content tokens emitted before tool decisions are
     *                       forwarded here so the user sees "thinking out loud" on stderr.
     */
    public String chatWithToolLoop(String model, List<LlmProvider.Message> conversation,
                                    List<ToolDefinition> tools, ToolExecutor executor,
                                    StreamHandlers handlers, int maxIterations,
                                    boolean appendFinalAssistant,
                                    Consumer<String> verboseHandler)
            throws IOException, LlmProvider.LlmException {

        boolean toolsLoaded = false;
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            // After the first round the model has seen tool schemas in its own assistant messages;
            // resending the full tool definitions costs ~2400 tokens per iteration for no benefit.
            List<ToolDefinition> iterationTools = toolsLoaded ? null : tools;
            ChatResponse response = verboseHandler != null
                ? chatWithToolsStreaming(model, conversation, iterationTools, verboseHandler)
                : chatWithTools(model, conversation, iterationTools);
            toolsLoaded = true;

            if (!response.hasToolCalls()) {
                // Final response — stream it for real-time output
                if (response.content() != null && !response.content().isEmpty()) {
                    if (handlers != null && handlers.responseHandler() != null) {
                        handlers.responseHandler().accept(response.content());
                    }
                    if (appendFinalAssistant) {
                        conversation.add(new LlmProvider.Message("assistant", response.content()));
                    }
                    return response.content();
                }
                // Content was null/empty — use streaming for the final answer.
                // Compact tool exchanges into a single context message to reduce token count.
                List<LlmProvider.Message> compacted = compactForFinalAnswer(conversation);
                String streamed = chat(model, compacted, handlers);
                if (appendFinalAssistant && !streamed.isEmpty()) {
                    // Append to original conversation (not the compacted view)
                    conversation.add(new LlmProvider.Message("assistant", streamed));
                }
                return streamed;
            }

            // Model wants to call tools
            conversation.add(LlmProvider.Message.assistantWithToolCalls(response.rawToolCalls()));

            // Execute each tool call and add results
            for (ToolCall call : response.toolCalls()) {
                System.err.println("  [tool] " + call.name() + "(" + formatArgs(call.arguments()) + ")");
                String result = executor.execute(call);
                if (result.length() > 8000) {
                    result = result.substring(0, 8000) + "\n... (truncated to 8000 chars)";
                }
                conversation.add(LlmProvider.Message.toolResult(call.id(), result));
            }

            // After the first round, compact old tool exchanges to keep context lean.
            // Keep the 2 most recent tool exchanges; summarize earlier ones.
            if (iteration >= 2) {
                compactIntermediateHistory(conversation);
            }
        }

        // Exceeded max iterations — stream the final response, compacting tool history
        System.err.println("  [tools] Max iterations reached, generating final answer...");
        List<LlmProvider.Message> compacted = compactForFinalAnswer(conversation);
        String finalContent = chat(model, compacted, handlers);
        if (appendFinalAssistant && !finalContent.isEmpty()) {
            conversation.add(new LlmProvider.Message("assistant", finalContent));
        }
        return finalContent;
    }

    /**
     * In-place compaction of old tool exchanges during the tool loop.
     * Keeps the last 2 assistant+tool pairs intact; replaces earlier ones with
     * a summarized user message to cap context growth.
     */
    private static void compactIntermediateHistory(List<LlmProvider.Message> conversation) {
        // Find all tool-exchange segments: each is (assistant-with-tool-calls, tool-result+)
        // We keep the last 2 full segments and collapse everything before that
        List<Integer> segmentStarts = new ArrayList<>(); // index of each assistant-with-tool-calls message
        for (int i = 0; i < conversation.size(); i++) {
            LlmProvider.Message m = conversation.get(i);
            if ("assistant".equals(m.role()) && m.toolCalls() != null) {
                segmentStarts.add(i);
            }
        }
        if (segmentStarts.size() <= 2) return; // not enough to compact

        // Collapse everything from the first tool segment to the start of (size-2)-th segment
        int keepFrom = segmentStarts.get(segmentStarts.size() - 2);
        int collapseFrom = segmentStarts.get(0);

        // Build summary from tool results in [collapseFrom, keepFrom)
        StringBuilder summary = new StringBuilder("\n\nPrevious tool findings (summarized):\n");
        for (int i = collapseFrom; i < keepFrom; i++) {
            LlmProvider.Message m = conversation.get(i);
            if ("tool".equals(m.role()) && m.content() != null && !m.content().isBlank()) {
                String content = m.content();
                if (content.length() > 1000) content = content.substring(0, 1000) + "...";
                summary.append("- ").append(content.lines().findFirst().orElse("")).append("\n");
            }
        }

        // Replace [collapseFrom, keepFrom) with a single user summary message
        List<LlmProvider.Message> remaining = new ArrayList<>(conversation.subList(keepFrom, conversation.size()));
        conversation.subList(collapseFrom, conversation.size()).clear();

        // Append summary to last user message before collapseFrom (if any)
        for (int i = conversation.size() - 1; i >= 0; i--) {
            if ("user".equals(conversation.get(i).role()) && conversation.get(i).toolCalls() == null) {
                LlmProvider.Message old = conversation.get(i);
                conversation.set(i, new LlmProvider.Message("user", old.content() + summary));
                break;
            }
        }
        conversation.addAll(remaining);
    }

    /**
     * call/result exchanges with a single summarized user message.
     *
     * <p>This reduces token count significantly: tool definitions (hundreds of tokens)
     * are dropped, and repeated tool exchanges are collapsed into a short summary.
     * The system prompt and original user question are preserved verbatim.
     */
    private static List<LlmProvider.Message> compactForFinalAnswer(List<LlmProvider.Message> conversation) {
        // Find tool exchanges: messages after the first user message with role assistant/tool
        List<LlmProvider.Message> system = new ArrayList<>();
        List<LlmProvider.Message> beforeTools = new ArrayList<>();
        List<LlmProvider.Message> toolExchanges = new ArrayList<>();

        boolean pastFirstUser = false;
        boolean inToolPhase = false;
        for (LlmProvider.Message m : conversation) {
            if (!pastFirstUser) {
                if ("user".equals(m.role())) {
                    pastFirstUser = true;
                    beforeTools.add(m);
                } else {
                    system.add(m);
                }
            } else if (!inToolPhase) {
                if (m.toolCalls() != null || "tool".equals(m.role())) {
                    inToolPhase = true;
                    toolExchanges.add(m);
                } else {
                    beforeTools.add(m);
                }
            } else {
                toolExchanges.add(m);
            }
        }

        if (toolExchanges.isEmpty()) {
            return conversation; // no tool calls — nothing to compact
        }

        // Build a summary of what the tools found
        StringBuilder summary = new StringBuilder();
        summary.append("\n\nAdditional data collected via tools:\n");
        for (LlmProvider.Message m : toolExchanges) {
            if ("tool".equals(m.role()) && m.content() != null && !m.content().isBlank()) {
                String content = m.content();
                if (content.length() > 2000) content = content.substring(0, 2000) + "\n...(truncated)";
                summary.append("\n---\n").append(content);
            }
        }

        // Merge the summary into the user message
        List<LlmProvider.Message> result = new ArrayList<>(system);
        for (int i = 0; i < beforeTools.size(); i++) {
            LlmProvider.Message m = beforeTools.get(i);
            if (i == beforeTools.size() - 1 && "user".equals(m.role())) {
                // Append tool summary to the last user message
                result.add(new LlmProvider.Message("user", m.content() + summary));
            } else {
                result.add(m);
            }
        }
        return result;
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
