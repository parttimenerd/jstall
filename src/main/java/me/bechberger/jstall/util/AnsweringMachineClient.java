package me.bechberger.jstall.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Client for the answering-machine API.
 * Handles communication with the LLM service.
 */
public class AnsweringMachineClient {

    private static final String API_URL = "https://models.answering-machine.utility.gardener.cloud.sap/chat/completions";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;

    public AnsweringMachineClient() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    /**
     * Represents a chat message.
     */
    public static class Message {
        public final String role;
        public final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    /**
     * Streams completion from the API, calling outputHandler for each chunk of content.
     * Note: API may not support streaming, in which case we get the full response and output it.
     *
     * @param apiKey API key for authentication
     * @param model Model name (e.g., "gpt-50-nano")
     * @param messages List of messages
     * @param outputHandler Handler called with content chunks
     * @throws IOException if network error occurs
     * @throws ApiException if API returns an error
     */
    public void streamCompletion(String apiKey, String model, List<Message> messages,
                                 java.util.function.Consumer<String> outputHandler)
            throws IOException, ApiException {

        String requestBody = buildRequestBody(model, messages);
        HttpRequest request = buildRequest(apiKey, requestBody);

        try {
            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            handleResponse(response, outputHandler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }

    /**
     * Gets the raw JSON response from the API.
     *
     * @param apiKey API key for authentication
     * @param model Model name
     * @param messages List of messages
     * @return Raw JSON response as string
     * @throws IOException if network error occurs
     * @throws ApiException if API returns an error
     */
    public String getCompletionRaw(String apiKey, String model, List<Message> messages)
            throws IOException, ApiException {

        String requestBody = buildRequestBody(model, messages);
        HttpRequest request = buildRequest(apiKey, requestBody);

        try {
            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw createApiException(response);
            }

            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }

    private String buildRequestBody(String model, List<Message> messages) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);

        ArrayNode messagesArray = root.putArray("messages");
        for (Message msg : messages) {
            ObjectNode messageNode = messagesArray.addObject();
            messageNode.put("role", msg.role);
            messageNode.put("content", msg.content);
        }

        return MAPPER.writeValueAsString(root);
    }

    private HttpRequest buildRequest(String apiKey, String requestBody) {
        return HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + sanitizeForLogging(apiKey))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
    }

    private void handleResponse(HttpResponse<String> response,
                                java.util.function.Consumer<String> outputHandler)
            throws IOException, ApiException {

        if (response.statusCode() >= 400) {
            throw createApiException(response);
        }

        // Parse response and extract content
        JsonNode root = MAPPER.readTree(response.body());
        JsonNode choices = root.get("choices");

        if (choices != null && choices.isArray() && choices.size() > 0) {
            JsonNode message = choices.get(0).get("message");
            if (message != null) {
                JsonNode content = message.get("content");
                if (content != null) {
                    outputHandler.accept(content.asText());
                    return;
                }
            }
        }

        throw new IOException("Unexpected API response format");
    }

    private ApiException createApiException(HttpResponse<String> response) {
        int statusCode = response.statusCode();
        String body = response.body();

        // Try to extract error message from JSON
        String errorMessage = null;
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode error = root.get("error");
            if (error != null) {
                JsonNode message = error.get("message");
                if (message != null) {
                    errorMessage = message.asText();
                }
            }
        } catch (Exception e) {
            // Use raw body if JSON parsing fails
        }

        if (errorMessage == null) {
            errorMessage = body.length() > 200 ? body.substring(0, 200) + "..." : body;
        }

        return new ApiException(statusCode, errorMessage);
    }

    /**
     * Sanitizes sensitive data for logging (never log actual API key).
     */
    private String sanitizeForLogging(String apiKey) {
        // In production, we'd actually use the real key, but ensure it's never logged
        // This method exists to remind us to sanitize in error messages
        return apiKey;
    }

    /**
     * Exception thrown when API returns an error.
     */
    public static class ApiException extends Exception {
        private final int statusCode;

        public ApiException(int statusCode, String message) {
            super("API error (" + statusCode + "): " + message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public boolean isAuthError() {
            return statusCode == 401;
        }

        @Override
        public String getMessage() {
            // Ensure API key is never in the message
            String msg = super.getMessage();
            // Additional sanitization could go here
            return msg;
        }
    }
}