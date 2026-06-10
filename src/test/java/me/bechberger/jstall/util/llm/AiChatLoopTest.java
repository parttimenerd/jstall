package me.bechberger.jstall.util.llm;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AiChatLoop using a fake OpenAiLlmProvider and scripted stdin.
 */
class AiChatLoopTest {

    /** Minimal stub — always returns a canned reply, no tool calls. */
    private static class FakeProvider extends OpenAiLlmProvider {
        private final List<String> capturedUserMessages = new ArrayList<>();
        private String reply = "The answer is 42.";

        FakeProvider() {
            super("http://localhost:9999"); // no real connection
        }

        @Override
        public String chatWithToolLoop(String model,
                                        List<LlmProvider.Message> conversation,
                                        List<ToolDefinition> tools,
                                        ToolExecutor executor,
                                        LlmProvider.StreamHandlers handlers,
                                        int maxIterations,
                                        boolean appendFinalAssistant) {
            return chatWithToolLoop(model, conversation, tools, executor, handlers,
                maxIterations, appendFinalAssistant, null);
        }

        @Override
        public String chatWithToolLoop(String model,
                                        List<LlmProvider.Message> conversation,
                                        List<ToolDefinition> tools,
                                        ToolExecutor executor,
                                        LlmProvider.StreamHandlers handlers,
                                        int maxIterations,
                                        boolean appendFinalAssistant,
                                        java.util.function.Consumer<String> verboseHandler) {
            // Record the last user message
            for (int i = conversation.size() - 1; i >= 0; i--) {
                LlmProvider.Message m = conversation.get(i);
                if ("user".equals(m.role())) {
                    capturedUserMessages.add(m.content());
                    break;
                }
            }
            // Emit reply
            if (handlers != null && handlers.responseHandler() != null) {
                handlers.responseHandler().accept(reply);
            }
            if (appendFinalAssistant) {
                conversation.add(new LlmProvider.Message("assistant", reply));
            }
            return reply;
        }
    }

    private AiChatLoop buildLoop(FakeProvider provider, String stdinContent,
                                  List<LlmProvider.Message> history) throws Exception {
        // Redirect System.in
        System.setIn(new ByteArrayInputStream(stdinContent.getBytes(StandardCharsets.UTF_8)));

        return new AiChatLoop(
            provider,
            "test-model",
            history,
            List.of(),
            call -> "tool-not-used",
            false,
            false
        );
    }

    @Test
    void exitCommandStopsLoop() throws Exception {
        FakeProvider provider = new FakeProvider();
        List<LlmProvider.Message> history = new ArrayList<>();
        history.add(new LlmProvider.Message("system", "You are helpful."));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));

        AiChatLoop loop = buildLoop(provider, "/exit\n", history);
        loop.run();

        assertTrue(provider.capturedUserMessages.isEmpty(), "No messages should be sent");
    }

    @Test
    void quitCommandStopsLoop() throws Exception {
        FakeProvider provider = new FakeProvider();
        List<LlmProvider.Message> history = new ArrayList<>();
        history.add(new LlmProvider.Message("system", "You are helpful."));

        AiChatLoop loop = buildLoop(provider, "/quit\n", history);
        loop.run();

        assertTrue(provider.capturedUserMessages.isEmpty());
    }

    @Test
    void userMessageSentToProvider() throws Exception {
        FakeProvider provider = new FakeProvider();
        List<LlmProvider.Message> history = new ArrayList<>();
        history.add(new LlmProvider.Message("system", "You are helpful."));

        // Type a question then exit
        AiChatLoop loop = buildLoop(provider, "What is 6*7?\n/exit\n", history);
        loop.run();

        assertEquals(1, provider.capturedUserMessages.size());
        assertEquals("What is 6*7?", provider.capturedUserMessages.get(0));
    }

    @Test
    void historyGrowsAcrossTurns() throws Exception {
        FakeProvider provider = new FakeProvider();
        List<LlmProvider.Message> history = new ArrayList<>();
        history.add(new LlmProvider.Message("system", "You are helpful."));

        AiChatLoop loop = buildLoop(provider, "First question\nSecond question\n/exit\n", history);
        loop.run();

        assertEquals(2, provider.capturedUserMessages.size());
        // History should contain: system + user1 + assistant1 + user2 + assistant2
        long userCount = history.stream().filter(m -> "user".equals(m.role())).count();
        long assistantCount = history.stream().filter(m -> "assistant".equals(m.role())).count();
        assertEquals(2, userCount);
        assertEquals(2, assistantCount);
    }

    @Test
    void clearResetsHistoryKeepsSystem() throws Exception {
        FakeProvider provider = new FakeProvider();
        List<LlmProvider.Message> history = new ArrayList<>();
        history.add(new LlmProvider.Message("system", "You are helpful."));
        // Add some prior conversation
        history.add(new LlmProvider.Message("user", "old question"));
        history.add(new LlmProvider.Message("assistant", "old answer"));

        AiChatLoop loop = buildLoop(provider, "/clear\n/exit\n", history);
        loop.run();

        assertEquals(1, history.size(), "Only system message should remain");
        assertEquals("system", history.get(0).role());
    }

    @Test
    void helpCommandDoesNotSendMessage() throws Exception {
        FakeProvider provider = new FakeProvider();
        List<LlmProvider.Message> history = new ArrayList<>();
        history.add(new LlmProvider.Message("system", "You are helpful."));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));

        AiChatLoop loop = buildLoop(provider, "/help\n/exit\n", history);
        loop.run();

        assertTrue(provider.capturedUserMessages.isEmpty());
        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("/exit") || output.contains("exit"), "Help should mention exit: " + output);
    }

    @Test
    void eofExitsGracefully() throws Exception {
        FakeProvider provider = new FakeProvider();
        List<LlmProvider.Message> history = new ArrayList<>();
        history.add(new LlmProvider.Message("system", "You are helpful."));

        // Empty stdin — immediate EOF
        AiChatLoop loop = buildLoop(provider, "", history);
        // Should not throw
        loop.run();
    }
}
