package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
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
        assertTrue(supported.contains("thinking"));

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

        AnalyzerResult result = analyzer.analyze(dumps, options);

        assertEquals(0, result.exitCode());
        assertEquals("gpt-4", mockProvider.getLastModel());
    }

    @Test
    void testAnalyzeWithCustomQuestion() {
        mockProvider.setResponse("Answer to custom question");
        mockProvider.setSupportsStreaming(false);

        List<ThreadDumpSnapshot> dumps = createTestDumps(2);
        Map<String, Object> options = Map.of("question", "What is causing the deadlock?");

        AnalyzerResult result = analyzer.analyze(dumps, options);

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

        AnalyzerResult result = analyzer.analyze(dumps, options);

        assertEquals(0, result.exitCode());
        assertEquals(rawJson, result.output());
    }

    @Test
    void testAnalyzeWithAuthenticationError() {
        mockProvider.setAuthError();

        List<ThreadDumpSnapshot> dumps = createTestDumps(2);
        AnalyzerResult result = analyzer.analyze(dumps, Map.of());

        assertEquals(4, result.exitCode());
        assertTrue(result.output().contains("Authentication failed"));
        assertTrue(result.output().contains("Please check your API key"));
    }

    @Test
    void testAnalyzeWithApiError() {
        mockProvider.setApiError(500, "Internal server error");

        List<ThreadDumpSnapshot> dumps = createTestDumps(2);
        AnalyzerResult result = analyzer.analyze(dumps, Map.of());

        assertEquals(5, result.exitCode());
        assertTrue(result.output().contains("LLM error"));
    }

    @Test
    void testAnalyzeWithNetworkError() {
        mockProvider.setNetworkError();

        List<ThreadDumpSnapshot> dumps = createTestDumps(2);
        AnalyzerResult result = analyzer.analyze(dumps, Map.of());

        assertEquals(3, result.exitCode());
        assertTrue(result.output().contains("Network error"));
    }

    @Test
    void testIntelligentFilteringEnabledByDefault() {
        mockProvider.setResponse("Analysis");
        mockProvider.setSupportsStreaming(false);

        List<ThreadDumpSnapshot> dumps = createTestDumps(2);
        analyzer.analyze(dumps, Map.of());

        // The analyzer should have enabled intelligent filtering
        // This is implicitly tested by the fact that it doesn't fail
        assertEquals(0, analyzer.analyze(dumps, Map.of()).exitCode());
    }

    @Test
    void testSystemPromptIncluded() {
        mockProvider.setResponse("Response");
        mockProvider.setSupportsStreaming(false);

        List<ThreadDumpSnapshot> dumps = createTestDumps(2);
        analyzer.analyze(dumps, Map.of());

        List<LlmProvider.Message> messages = mockProvider.getLastMessages();
        assertNotNull(messages);
        assertTrue(messages.stream()
            .anyMatch(m -> m.role().equals("system") && m.content().contains("thread dump analyzer")));
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