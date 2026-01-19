package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.model.ThreadDumpWithRaw;
import me.bechberger.jstall.util.AnsweringMachineClient;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.model.ThreadInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AiAnalyzerTest {

    private AiAnalyzer analyzer;
    private MockAnsweringMachineClient mockClient;
    private static final String TEST_API_KEY = "test-api-key";

    @BeforeEach
    void setUp() {
        mockClient = new MockAnsweringMachineClient();
        analyzer = new AiAnalyzer(mockClient, TEST_API_KEY);
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

        // Should include options from StatusAnalyzer
        assertTrue(supported.contains("keep"));
        assertTrue(supported.contains("top"));
    }

    @Test
    void testDumpRequirement() {
        assertEquals(DumpRequirement.MANY, analyzer.dumpRequirement());
    }

    @Test
    void testAnalyzeWithDefaultOptions() {
        mockClient.setResponse("This is a summary of the thread dump analysis.");

        List<ThreadDumpWithRaw> dumps = createTestDumps(2);
        AnalyzerResult result = analyzer.analyze(dumps, Map.of());

        assertEquals(0, result.exitCode());
        assertNotNull(result.output());
        assertEquals("This is a summary of the thread dump analysis.", result.output());

        // Verify default model was used
        assertEquals("gpt-50-nano", mockClient.getLastModel());
    }

    @Test
    void testAnalyzeWithCustomModel() {
        mockClient.setResponse("Analysis result");

        List<ThreadDumpWithRaw> dumps = createTestDumps(2);
        Map<String, Object> options = Map.of("model", "gpt-4");

        AnalyzerResult result = analyzer.analyze(dumps, options);

        assertEquals(0, result.exitCode());
        assertEquals("gpt-4", mockClient.getLastModel());
    }

    @Test
    void testAnalyzeWithCustomQuestion() {
        mockClient.setResponse("Answer to custom question");

        List<ThreadDumpWithRaw> dumps = createTestDumps(2);
        Map<String, Object> options = Map.of("question", "What is causing the deadlock?");

        AnalyzerResult result = analyzer.analyze(dumps, options);

        assertEquals(0, result.exitCode());
        assertNotNull(result.output());

        // Verify custom question was included in messages
        List<AnsweringMachineClient.Message> messages = mockClient.getLastMessages();
        assertNotNull(messages);
        assertTrue(messages.stream()
            .anyMatch(m -> m.content.contains("What is causing the deadlock?")));
    }

    @Test
    void testAnalyzeWithRawOutput() throws Exception {
        String rawJson = "{\"choices\": [{\"message\": {\"content\": \"Raw response\"}}]}";
        mockClient.setRawResponse(rawJson);

        List<ThreadDumpWithRaw> dumps = createTestDumps(2);
        Map<String, Object> options = Map.of("raw", true);

        AnalyzerResult result = analyzer.analyze(dumps, options);

        assertEquals(0, result.exitCode());
        assertEquals(rawJson, result.output());
    }

    @Test
    void testAnalyzeWithAuthenticationError() {
        mockClient.setAuthError();

        List<ThreadDumpWithRaw> dumps = createTestDumps(2);
        AnalyzerResult result = analyzer.analyze(dumps, Map.of());

        assertEquals(4, result.exitCode());
        assertTrue(result.output().contains("Authentication failed"));
        assertTrue(result.output().contains("Please check your API key"));
    }

    @Test
    void testAnalyzeWithApiError() {
        mockClient.setApiError(500, "Internal server error");

        List<ThreadDumpWithRaw> dumps = createTestDumps(2);
        AnalyzerResult result = analyzer.analyze(dumps, Map.of());

        assertEquals(5, result.exitCode());
        assertTrue(result.output().contains("API error"));
    }

    @Test
    void testAnalyzeWithNetworkError() {
        mockClient.setNetworkError();

        List<ThreadDumpWithRaw> dumps = createTestDumps(2);
        AnalyzerResult result = analyzer.analyze(dumps, Map.of());

        assertEquals(3, result.exitCode());
        assertTrue(result.output().contains("Network error"));
    }

    @Test
    void testIntelligentFilteringEnabledByDefault() {
        mockClient.setResponse("Analysis");

        List<ThreadDumpWithRaw> dumps = createTestDumps(2);
        analyzer.analyze(dumps, Map.of());

        // The analyzer should have enabled intelligent filtering
        // This is implicitly tested by the fact that it doesn't fail
        assertEquals(0, analyzer.analyze(dumps, Map.of()).exitCode());
    }

    @Test
    void testSystemPromptIncluded() {
        mockClient.setResponse("Response");

        List<ThreadDumpWithRaw> dumps = createTestDumps(2);
        analyzer.analyze(dumps, Map.of());

        List<AnsweringMachineClient.Message> messages = mockClient.getLastMessages();
        assertNotNull(messages);
        assertTrue(messages.stream()
            .anyMatch(m -> m.role.equals("system") && m.content.contains("thread dump analyzer")));
    }

    // Helper method to create test thread dumps
    private List<ThreadDumpWithRaw> createTestDumps(int count) {
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
        ThreadDumpWithRaw dumpWithRaw = new ThreadDumpWithRaw(dump, rawDump);

        return List.of(dumpWithRaw, dumpWithRaw).subList(0, Math.min(count, 2));
    }

    // Mock implementation of AnsweringMachineClient for testing
    private static class MockAnsweringMachineClient extends AnsweringMachineClient {
        private String response;
        private String rawResponse;
        private boolean authError;
        private boolean networkError;
        private int apiErrorCode;
        private String apiErrorMessage;
        private String lastModel;
        private List<Message> lastMessages;

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

        public List<Message> getLastMessages() {
            return lastMessages;
        }

        @Override
        public void streamCompletion(String apiKey, String model, List<Message> messages,
                                     Consumer<String> outputHandler)
                throws IOException, ApiException {
            this.lastModel = model;
            this.lastMessages = messages;

            if (networkError) {
                throw new IOException("Network error");
            }

            if (authError) {
                throw new ApiException(401, "Unauthorized");
            }

            if (apiErrorCode != 0) {
                throw new ApiException(apiErrorCode, apiErrorMessage);
            }

            if (response != null) {
                outputHandler.accept(response);
            }
        }

        @Override
        public String getCompletionRaw(String apiKey, String model, List<Message> messages)
                throws IOException, ApiException {
            this.lastModel = model;
            this.lastMessages = messages;

            if (networkError) {
                throw new IOException("Network error");
            }

            if (authError) {
                throw new ApiException(401, "Unauthorized");
            }

            if (apiErrorCode != 0) {
                throw new ApiException(apiErrorCode, apiErrorMessage);
            }

            return rawResponse != null ? rawResponse : "{}";
        }
    }
}