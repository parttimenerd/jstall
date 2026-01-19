package me.bechberger.jstall.util;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * Abstraction for LLM providers (Gardener AI, Ollama, etc.).
 */
public interface LlmProvider {

    /**
     * Represents a chat message with role and content.
     */
    class Message {
        public final String role;
        public final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    /**
     * Handlers for streaming responses.
     */
    class StreamHandlers {
        public final Consumer<String> responseHandler;
        public final Consumer<String> thinkingHandler;

        public StreamHandlers(Consumer<String> responseHandler, Consumer<String> thinkingHandler) {
            this.responseHandler = responseHandler;
            this.thinkingHandler = thinkingHandler;
        }

        public StreamHandlers(Consumer<String> responseHandler) {
            this(responseHandler, null);
        }
    }

    /**
     * Indicates whether this provider supports true token-by-token streaming.
     * If false, the response handler will be called once with the complete response.
     *
     * <p>This is used by consumers to:
     * <ul>
     * <li>Determine if thinking tokens can be displayed separately</li>
     * <li>Decide whether to show "streaming" UI indicators</li>
     * <li>Warn users if they request features that require streaming (e.g., --thinking)</li>
     * </ul>
     *
     * @return true if provider supports streaming, false otherwise
     */
    default boolean supportsStreaming() {
        return false;
    }

    /**
     * Returns provider-specific additional instructions to include in prompts.
     * These instructions help tailor the AI behavior for the specific provider's characteristics.
     *
     * @return Additional instructions string, or empty string if none
     */
    default String getAdditionalInstructions() {
        return "";
    }

    /**
     * Chat with the LLM using the specified model and messages.
     *
     * @param model Model name
     * @param messages List of messages
     * @param handlers Stream handlers for response and thinking tokens
     * @return The complete response text
     * @throws IOException if network error occurs
     * @throws LlmException if LLM returns an error
     */
    String chat(String model, List<Message> messages, StreamHandlers handlers)
            throws IOException, LlmException;

    /**
     * Get raw JSON response (for debugging/raw output mode).
     *
     * @param model Model name
     * @param messages List of messages
     * @return Raw JSON response
     * @throws IOException if network error occurs
     * @throws LlmException if LLM returns an error
     */
    String getRawResponse(String model, List<Message> messages)
            throws IOException, LlmException;

    /**
     * Exception thrown by LLM providers.
     */
    class LlmException extends Exception {
        private final int statusCode;

        public LlmException(String message) {
            this(message, -1);
        }

        public LlmException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
            this.statusCode = -1;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public boolean isAuthError() {
            return statusCode == 401 || statusCode == 403;
        }
    }
}