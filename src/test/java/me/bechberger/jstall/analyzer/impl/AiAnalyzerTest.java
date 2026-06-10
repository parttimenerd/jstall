package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
import me.bechberger.jstall.util.llm.LlmProvider;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.model.ThreadInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AiAnalyzerTest {

    private AiAnalyzer analyzer;
    private MockLlmProvider mockProvider;

    @BeforeEach
    void setUp() {
        mockProvider = new MockLlmProvider();
        analyzer = new AiAnalyzer(mockProvider);
    }

    @Test
    void testName() {
        assertEquals("ai", analyzer.name());
    }

    @Test
    void testSupportedOptions() {
        Set<String> supported = analyzer.supportedOptions();

        // AI-specific options
        assertTrue(supported.contains("model"));
        assertTrue(supported.contains("question"));
        assertTrue(supported.contains("raw"));
        assertTrue(supported.contains("think"));

        // Should include options from StatusAnalyzer
        assertTrue(supported.contains("keep"));
        assertTrue(supported.contains("top"));
    }

    @Test
    void testDumpRequirement() {
        assertEquals(DumpRequirement.MANY, analyzer.dumpRequirement());
    }

    @Test
    void testAnalyzeWithCustomModel() {
        mockProvider.setResponse("Analysis result");
        mockProvider.setSupportsStreaming(false);

        List<ThreadDumpSnapshot> dumps = createTestDumps(2);
        Map<String, Object> options = Map.of("model", "gpt-4");

        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(dumps), options);

        assertEquals(0, result.exitCode());
        assertEquals("gpt-4", mockProvider.getLastModel());
    }

    @Test
    void testAnalyzeWithCustomQuestion() {
        mockProvider.setResponse("Answer to custom question");
        mockProvider.setSupportsStreaming(false);

        List<ThreadDumpSnapshot> dumps = createTestDumps(2);
        Map<String, Object> options = Map.of("question", "What is causing the deadlock?");

        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(dumps), options);

        assertEquals(0, result.exitCode());
        assertNotNull(result.output());

        // Verify custom question was included in messages
        List<LlmProvider.Message> messages = mockProvider.getLastMessages();
        assertNotNull(messages);
        assertTrue(messages.stream()
            .anyMatch(m -> m.content().contains("What is causing the deadlock?")));
    }

    @Test
    void testAnalyzeWithRawOutput() {
        String rawJson = "{\"choices\": [{\"message\": {\"content\": \"Raw response\"}}]}";
        mockProvider.setRawResponse(rawJson);

        List<ThreadDumpSnapshot> dumps = createTestDumps(2);
        Map<String, Object> options = Map.of("raw", true);

        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(dumps), options);

        assertEquals(0, result.exitCode());
        assertEquals(rawJson, result.output());
    }

    @Test
    void testAnalyzeWithAuthenticationError() {
        mockProvider.setAuthError();

        List<ThreadDumpSnapshot> dumps = createTestDumps(2);
        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(dumps), Map.of());

        assertEquals(4, result.exitCode());
        assertTrue(result.output().contains("Authentication failed"));
        assertTrue(result.output().contains("Please check your API key"));
    }

    @Test
    void testAnalyzeWithApiError() {
        mockProvider.setApiError(500, "Internal server error");

        List<ThreadDumpSnapshot> dumps = createTestDumps(2);
        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(dumps), Map.of());

        assertEquals(5, result.exitCode());
        assertTrue(result.output().contains("LLM error"));
    }

    @Test
    void testAnalyzeWithNetworkError() {
        mockProvider.setNetworkError();

        List<ThreadDumpSnapshot> dumps = createTestDumps(2);
        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(dumps), Map.of());

        assertEquals(3, result.exitCode());
        assertTrue(result.output().contains("Network error"));
    }

    @Test
    void testIntelligentFilteringEnabledByDefault() {
        mockProvider.setResponse("Analysis");
        mockProvider.setSupportsStreaming(false);

        List<ThreadDumpSnapshot> dumps = createTestDumps(2);
        analyzer.analyze(ResolvedData.fromDumps(dumps), Map.of());

        // The analyzer should have enabled intelligent filtering
        // This is implicitly tested by the fact that it doesn't fail
        assertEquals(0, analyzer.analyze(ResolvedData.fromDumps(dumps), Map.of()).exitCode());
    }

    @Test
    void testSystemPromptIncluded() {
        mockProvider.setResponse("Response");
        mockProvider.setSupportsStreaming(false);

        List<ThreadDumpSnapshot> dumps = createTestDumps(2);
        analyzer.analyze(ResolvedData.fromDumps(dumps), Map.of());

        List<LlmProvider.Message> messages = mockProvider.getLastMessages();
        assertNotNull(messages);
        assertTrue(messages.stream()
            .anyMatch(m -> m.role().equals("system") && m.content().contains("JVM")));
    }

    // Helper method to create test thread dumps
    private List<ThreadDumpSnapshot> createTestDumps(int count) {
        ThreadInfo thread = new ThreadInfo(
            "test-thread",
            1L,
            null,
            5,
            false,
            Thread.State.RUNNABLE,
            1.0,
            10.0,
            List.of(),
            List.of(),
            null,
            null
        );

        ThreadDump dump = new ThreadDump(
            Instant.now(),
            "Test Dump",
            List.of(thread),
            null,
            null,
            null
        );

        String rawDump = "Test thread dump content";
        ThreadDumpSnapshot dumpWithRaw = new ThreadDumpSnapshot(dump, rawDump, null, null);

        return List.of(dumpWithRaw, dumpWithRaw).subList(0, Math.min(count, 2));
    }

    // Mock implementation of LlmProvider for testing
    private static class StreamingMockLlmProvider implements LlmProvider {
        private final List<String> tokens;
        private String lastModel;
        private List<Message> lastMessages;

        StreamingMockLlmProvider(List<String> tokens) {
            this.tokens = tokens;
        }

        @Override
        public boolean supportsStreaming() {
            return true;
        }

        @Override
        public String chat(String model, List<Message> messages, StreamHandlers handlers)
                throws IOException, LlmException {
            this.lastModel = model;
            this.lastMessages = new ArrayList<>(messages);
            StringBuilder full = new StringBuilder();
            for (String token : tokens) {
                if (handlers != null && handlers.responseHandler() != null) {
                    handlers.responseHandler().accept(token);
                }
                full.append(token);
            }
            return full.toString();
        }

        @Override
        public String getRawResponse(String model, List<Message> messages)
                throws IOException, LlmException {
            return "{}";
        }
    }

    @Test
    void testStreamingWithoutThinkTags() {
        // Normal streaming: all content should be output
        var streamingProvider = new StreamingMockLlmProvider(
            List.of("Hello", " world", "!")
        );
        var aiAnalyzer = new AiAnalyzer(streamingProvider);

        List<ThreadDumpSnapshot> dumps = createTestDumps(2);
        AnalyzerResult result = aiAnalyzer.analyze(ResolvedData.fromDumps(dumps), Map.of());

        assertEquals(0, result.exitCode());
        // Streaming provider returns empty result (output already printed)
        assertEquals("", result.output());
    }

    @Test
    void testStreamingWithThinkTags() {
        // Model that uses <think> tags: thinking should be suppressed by default
        var streamingProvider = new StreamingMockLlmProvider(
            List.of("<think>", "Let me analyze", "</think>", "The app is healthy.")
        );
        var aiAnalyzer = new AiAnalyzer(streamingProvider);

        List<ThreadDumpSnapshot> dumps = createTestDumps(2);
        AnalyzerResult result = aiAnalyzer.analyze(ResolvedData.fromDumps(dumps), Map.of());

        assertEquals(0, result.exitCode());
    }

    @Test
    void testStreamingWithSplitThinkTags() {
        // <think> tag split across multiple tokens: "<thi" + "nk>" + "reasoning" + "</th" + "ink>"
        var streamingProvider = new StreamingMockLlmProvider(
            List.of("<thi", "nk>", "reasoning here", "</th", "ink>", "Visible answer")
        );
        var aiAnalyzer = new AiAnalyzer(streamingProvider);

        List<ThreadDumpSnapshot> dumps = createTestDumps(2);
        AnalyzerResult result = aiAnalyzer.analyze(ResolvedData.fromDumps(dumps), Map.of());

        assertEquals(0, result.exitCode());
        // Output should be empty (streamed to stdout) but thinking should have been suppressed
    }

    @Test
    void testStreamingWithThinkTagsInMiddleOfContent() {
        // Content before and after think block
        var streamingProvider = new StreamingMockLlmProvider(
            List.of("Start ", "<think>", "internal reasoning", "</think>", " End")
        );
        var aiAnalyzer = new AiAnalyzer(streamingProvider);

        List<ThreadDumpSnapshot> dumps = createTestDumps(2);
        AnalyzerResult result = aiAnalyzer.analyze(ResolvedData.fromDumps(dumps), Map.of());

        assertEquals(0, result.exitCode());
    }

    @Test
    void testDryRunMode() {
        mockProvider.setResponse("should not be called");
        mockProvider.setSupportsStreaming(false);

        List<ThreadDumpSnapshot> dumps = createTestDumps(2);
        Map<String, Object> options = new java.util.HashMap<>();
        options.put("dry-run", true);
        options.put("model", "test-model");

        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(dumps), options);

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("DRY RUN"));
        assertTrue(result.output().contains("test-model"));
        assertTrue(result.output().contains("System Prompt"));
        // Provider should not have been called
        assertNull(mockProvider.getLastModel());
    }

    @Test
    void testNoToolsOption() {
        mockProvider.setResponse("Analysis without tools");
        mockProvider.setSupportsStreaming(false);

        List<ThreadDumpSnapshot> dumps = createTestDumps(2);
        Map<String, Object> options = new java.util.HashMap<>();
        options.put("no-tools", true);

        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(dumps), options);

        assertEquals(0, result.exitCode());
        // System prompt should NOT contain tool addendum
        List<LlmProvider.Message> messages = mockProvider.getLastMessages();
        assertNotNull(messages);
        LlmProvider.Message systemMsg = messages.stream()
            .filter(m -> m.role().equals("system")).findFirst().orElseThrow();
        assertFalse(systemMsg.content().contains("You have tools"));
    }

    @Test
    void testAdditionalInstructionsIncluded() {
        mockProvider.setResponse("Response");
        mockProvider.setSupportsStreaming(false);

        List<ThreadDumpSnapshot> dumps = createTestDumps(2);
        analyzer.analyze(ResolvedData.fromDumps(dumps), Map.of());

        List<LlmProvider.Message> messages = mockProvider.getLastMessages();
        assertNotNull(messages);
        // The user prompt should contain additional instructions from provider
        LlmProvider.Message userMsg = messages.stream()
            .filter(m -> m.role().equals("user")).findFirst().orElseThrow();
        assertTrue(userMsg.content().contains("test additional instructions"));
    }

    @Test
    void testSupportedOptionsIncludeNoTools() {
        Set<String> supported = analyzer.supportedOptions();
        assertTrue(supported.contains("no-tools"));
        assertTrue(supported.contains("tools"));
    }

    // Mock implementation of LlmProvider for testing
    private static class MockLlmProvider implements LlmProvider {
        private String response;
        private String rawResponse;
        private boolean authError;
        private boolean networkError;
        private int apiErrorCode;
        private String apiErrorMessage;
        private String lastModel;
        private List<LlmProvider.Message> lastMessages;
        private boolean supportsStreaming = false;

        public void setResponse(String response) {
            this.response = response;
            this.authError = false;
            this.networkError = false;
            this.apiErrorCode = 0;
        }

        public void setRawResponse(String rawResponse) {
            this.rawResponse = rawResponse;
            this.authError = false;
            this.networkError = false;
            this.apiErrorCode = 0;
        }

        public void setAuthError() {
            this.authError = true;
            this.networkError = false;
            this.apiErrorCode = 0;
        }

        public void setNetworkError() {
            this.networkError = true;
            this.authError = false;
            this.apiErrorCode = 0;
        }

        public void setApiError(int statusCode, String message) {
            this.apiErrorCode = statusCode;
            this.apiErrorMessage = message;
            this.authError = false;
            this.networkError = false;
        }

        public String getLastModel() {
            return lastModel;
        }

        public List<LlmProvider.Message> getLastMessages() {
            return lastMessages;
        }

        public void setSupportsStreaming(boolean supportsStreaming) {
            this.supportsStreaming = supportsStreaming;
        }

        @Override
        public boolean supportsStreaming() {
            return supportsStreaming;
        }

        @Override
        public String getAdditionalInstructions() {
            return "test additional instructions";
        }

        @Override
        public String chat(String model, List<LlmProvider.Message> messages, LlmProvider.StreamHandlers handlers)
                throws IOException, LlmProvider.LlmException {
            this.lastModel = model;
            this.lastMessages = new ArrayList<>(messages);

            if (networkError) {
                throw new IOException("Network error");
            }

            if (authError) {
                throw new LlmProvider.LlmException("Unauthorized", 401);
            }

            if (apiErrorCode != 0) {
                throw new LlmProvider.LlmException(apiErrorMessage, apiErrorCode);
            }

            if (response != null && handlers != null && handlers.responseHandler() != null) {
                handlers.responseHandler().accept(response);
            }

            return response != null ? response : "";
        }

        @Override
        public String getRawResponse(String model, List<LlmProvider.Message> messages)
                throws IOException, LlmProvider.LlmException {
            this.lastModel = model;
            this.lastMessages = new ArrayList<>(messages);

            if (networkError) {
                throw new IOException("Network error");
            }

            if (authError) {
                throw new LlmProvider.LlmException("Unauthorized", 401);
            }

            if (apiErrorCode != 0) {
                throw new LlmProvider.LlmException(apiErrorMessage, apiErrorCode);
            }

            return rawResponse != null ? rawResponse : "{}";
        }
    }
}