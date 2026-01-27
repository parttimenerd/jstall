package me.bechberger.jstall.util.llm;

import me.bechberger.jstall.util.json.JsonParser;
import me.bechberger.jstall.util.json.JsonPrinter;
import me.bechberger.jstall.util.json.JsonValue;
import me.bechberger.jstall.util.json.JsonValue.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Client for the Gardener answering-machine API.
 * Handles communication with the LLM service.
 */
public class AnsweringMachineClient {

    private static final String API_URL = "https://models.answering-machine.utility.gardener.cloud.sap/chat/completions";

    private final HttpClient httpClient;

    public AnsweringMachineClient() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    /**
         * Represents a chat message.
         */
        public record Message(String role, String content) {
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

    private String buildRequestBody(String model, List<Message> messages) {
        // Build messages array
        JsonArray messagesArray = new JsonArray();
        for (Message msg : messages) {
            JsonObject messageObj = new JsonObject()
                .put("role", new JsonString(msg.role))
                .put("content", new JsonString(msg.content));
            messagesArray = messagesArray.add(messageObj);
        }

        // Build root object
        JsonObject root = new JsonObject()
            .put("model", new JsonString(model))
            .put("messages", messagesArray);

        return JsonPrinter.printCompact(root);
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
        try {
            JsonValue root = JsonParser.parse(response.body());
            String content = extractContentFromResponse(root);
            outputHandler.accept(content);
        } catch (JsonParser.JsonParseException e) {
            throw new IOException("Failed to parse API response", e);
        }
    }

    private ApiException createApiException(HttpResponse<String> response) {
        int statusCode = response.statusCode();
        String body = response.body();

        // Try to extract error message from JSON
        String errorMessage = extractErrorMessage(body);

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

    // Helper methods for JSON extraction

    /**
     * Extracts content string from API response JSON.
     * Expected structure: { "choices": [{ "message": { "content": "..." } }] }
     */
    private String extractContentFromResponse(JsonValue root) throws IOException {
        try {
            JsonObject obj = root.asObject();
            JsonArray choices = obj.get("choices").asArray();

            if (choices.isEmpty()) {
                throw new IOException("Unexpected API response format: empty choices array");
            }

            JsonObject firstChoice = choices.get(0).asObject();
            JsonObject message = firstChoice.get("message").asObject();
            return message.get("content").asString();
        } catch (IllegalStateException e) {
            throw new IOException("Unexpected API response format: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts error message from error response JSON.
     * Expected structure: { "error": { "message": "..." } }
     */
    private String extractErrorMessage(String body) {
        try {
            JsonValue root = JsonParser.parse(body);
            if (root.isObject()) {
                JsonObject obj = root.asObject();
                if (obj.has("error") && obj.get("error").isObject()) {
                    JsonObject error = obj.get("error").asObject();
                    if (error.has("message") && error.get("message").isString()) {
                        return error.get("message").asString();
                    }
                }
            }
        } catch (Exception e) {
            // Return null if parsing fails
        }
        return null;
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
    }
}