package me.bechberger.jstall.util;

import me.bechberger.jstall.util.AnsweringMachineClient.ApiException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM provider implementation for Gardener AI (answering-machine).
 */
public class GardenerLlmProvider implements LlmProvider {

    private final AnsweringMachineClient client;
    private final String apiKey;

    public GardenerLlmProvider(String apiKey) {
        this.client = new AnsweringMachineClient();
        this.apiKey = apiKey;
    }

    @Override
    public String chat(String model, List<LlmProvider.Message> messages, LlmProvider.StreamHandlers handlers)
            throws IOException, LlmProvider.LlmException {

        List<AnsweringMachineClient.Message> clientMessages = convertMessages(messages);
        StringBuilder response = new StringBuilder();

        try {
            client.streamCompletion(apiKey, model, clientMessages, content -> {
                response.append(content);
                if (handlers.responseHandler != null) {
                    handlers.responseHandler.accept(content);
                }
            });
            return response.toString();
        } catch (ApiException e) {
            throw new LlmProvider.LlmException("Gardener API error: " + e.getMessage(), e.getStatusCode());
        }
    }

    @Override
    public String getRawResponse(String model, List<LlmProvider.Message> messages)
            throws IOException, LlmProvider.LlmException {

        List<AnsweringMachineClient.Message> clientMessages = convertMessages(messages);
        try {
            return client.getCompletionRaw(apiKey, model, clientMessages);
        } catch (ApiException e) {
            throw new LlmProvider.LlmException("Gardener API error: " + e.getMessage(), e.getStatusCode());
        }
    }

    private List<AnsweringMachineClient.Message> convertMessages(List<LlmProvider.Message> messages) {
        List<AnsweringMachineClient.Message> clientMessages = new ArrayList<>();
        for (LlmProvider.Message msg : messages) {
            clientMessages.add(new AnsweringMachineClient.Message(msg.role, msg.content));
        }
        return clientMessages;
    }
}