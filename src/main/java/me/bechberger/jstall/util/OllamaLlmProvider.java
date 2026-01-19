package me.bechberger.jstall.util;

import io.github.ollama4j.Ollama;
import io.github.ollama4j.exceptions.OllamaException;
import io.github.ollama4j.models.chat.OllamaChatMessageRole;
import io.github.ollama4j.models.chat.OllamaChatRequest;
import io.github.ollama4j.models.chat.OllamaChatResult;
import io.github.ollama4j.models.chat.OllamaChatStreamObserver;
import io.github.ollama4j.models.generate.OllamaGenerateTokenHandler;
import io.github.ollama4j.models.request.ThinkMode;

import java.io.IOException;
import java.util.List;

/**
 * LLM provider implementation for Ollama (local models).
 */
public class OllamaLlmProvider implements LlmProvider {

    private final Ollama ollama;

    public OllamaLlmProvider(String host) {
        this.ollama = new Ollama(host);
        // Set timeout to 20 minutes (1200 seconds) for long-running requests
        this.ollama.setRequestTimeoutSeconds(1200);
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
    public String chat(String model, List<LlmProvider.Message> messages, LlmProvider.StreamHandlers handlers)
            throws IOException, LlmProvider.LlmException {

        try {
            // Build chat request using the builder pattern
            var builder = OllamaChatRequest.builder().withModel(model);

            for (LlmProvider.Message msg : messages) {
                OllamaChatMessageRole role = convertRole(msg.role);
                builder = builder.withMessage(role, msg.content);
            }

            // Enable or disable thinking mode based on whether thinking handler is provided
            if (handlers.thinkingHandler != null) {
                builder = builder.withThinking(ThinkMode.ENABLED);
            } else {
                builder = builder.withThinking(ThinkMode.DISABLED);
            }

            OllamaChatRequest request = builder.build();

            // Create separate handlers
            StringBuilder response = new StringBuilder();

            OllamaGenerateTokenHandler thinkingHandler = handlers.thinkingHandler != null
                ? handlers.thinkingHandler::accept
                : null;

            OllamaGenerateTokenHandler responseHandler = token -> {
                response.append(token);
                if (handlers.responseHandler != null) {
                    handlers.responseHandler.accept(token);
                }
            };

            // Create stream observer with separate handlers
            OllamaChatStreamObserver streamObserver = new OllamaChatStreamObserver(
                thinkingHandler,
                responseHandler
            );

            // Make the chat request
            ollama.chat(request, streamObserver);

            return response.toString();

        } catch (OllamaException e) {
            throw new LlmProvider.LlmException("Ollama error: " + e.getMessage(), e);
        }
    }

    @Override
    public String getRawResponse(String model, List<LlmProvider.Message> messages)
            throws LlmProvider.LlmException {

        try {
            // Build chat request using the builder pattern
            var builder = OllamaChatRequest.builder().withModel(model);

            for (LlmProvider.Message msg : messages) {
                OllamaChatMessageRole role = convertRole(msg.role);
                builder = builder.withMessage(role, msg.content);
            }

            OllamaChatRequest request = builder.build();

            // Get response (non-streaming)
            OllamaChatResult result = ollama.chat(request, null);

            // Return as JSON-like string (best effort)
            String responseText = result.getResponseModel().getMessage().getResponse();
            return "{\n  \"model\": \"" + model + "\",\n  \"response\": " +
                   escapeJson(responseText) + "\n}";

        } catch (OllamaException e) {
            throw new LlmProvider.LlmException("Ollama error: " + e.getMessage(), e);
        }
    }

    private OllamaChatMessageRole convertRole(String role) {
        return switch (role.toLowerCase()) {
            case "system" -> OllamaChatMessageRole.SYSTEM;
            case "user" -> OllamaChatMessageRole.USER;
            case "assistant" -> OllamaChatMessageRole.ASSISTANT;
            default -> OllamaChatMessageRole.USER;
        };
    }

    private String escapeJson(String text) {
        if (text == null) {
            return "null";
        }
        return "\"" + text.replace("\\", "\\\\")
                         .replace("\"", "\\\"")
                         .replace("\n", "\\n")
                         .replace("\r", "\\r")
                         .replace("\t", "\\t") + "\"";
    }
}