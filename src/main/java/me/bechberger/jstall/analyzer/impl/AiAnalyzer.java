package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.BaseAnalyzer;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
import me.bechberger.jstall.util.llm.LlmProvider;
import me.bechberger.jstall.util.SystemAnalyzer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI-powered thread dump analyzer using LLM.
 *
 * Runs StatusAnalyzer to get thread dump analysis, then sends it to an LLM
 * for intelligent insights and answers.
 */
public class AiAnalyzer extends BaseAnalyzer {

    private static final String SYSTEM_PROMPT =
        "You're a helpful thread dump analyzer that likes to be on the point. Given the following thread dump analysis, answer the user's question.";

    private static final String DEFAULT_USER_PROMPT =
        "Summarize the current state of the application. State any potential issues found in the thread dumps." +
        "Don't offer generic advice; focus on the specific findings from the analyses. But start with a short summary of the overall state.\n";

    private final LlmProvider llmProvider;

    /**
     * Creates an AI analyzer with the specified LLM provider.
     *
     * @param llmProvider The LLM provider to use
     */
    public AiAnalyzer(LlmProvider llmProvider) {
        this.llmProvider = llmProvider;
    }

    @Override
    public String name() {
        return "ai";
    }

    @Override
    public Set<String> supportedOptions() {
        // Support all status options plus AI-specific ones
        Set<String> options = new java.util.HashSet<>(new StatusAnalyzer().supportedOptions());
        options.add("model");
        options.add("question");
        options.add("raw");
        options.add("dry-run");
        options.add("short");
        options.add("thinking");
        return options;
    }

    @Override
    public DumpRequirement dumpRequirement() {
        return DumpRequirement.MANY;
    }

    @Override
    public AnalyzerResult analyze(List<ThreadDumpSnapshot> dumps, Map<String, Object> options) {
        // Extract AI-specific options
        String model = getStringOption(options, "model", "gpt-50-nano");
        String customQuestion = getStringOption(options, "question", null);
        boolean rawOutput = getBooleanOption(options, "raw", false);
        boolean dryRun = getBooleanOption(options, "dry-run", false);
        boolean shortMode = getBooleanOption(options, "short", false);
        boolean showThinking = getBooleanOption(options, "thinking", false);

        // Enable intelligent filtering by default
        Map<String, Object> statusOptions = new HashMap<>(options);

        // Run status analyzer to get thread dump analysis
        StatusAnalyzer statusAnalyzer = new StatusAnalyzer();
        AnalyzerResult statusResult = statusAnalyzer.analyze(dumps, statusOptions);

        if (statusResult.exitCode() != 0 && statusResult.exitCode() != 2) {
            // Status analyzer failed (but deadlocks are ok - exitCode 2)
            return AnalyzerResult.withExitCode(
                "Failed to analyze thread dumps: " + statusResult.output(),
                1
            );
        }

        String analysis = statusResult.output();

        // Build prompts
        String userPrompt = buildUserPrompt(analysis, customQuestion);

        // Dry-run mode: just print the prompt without calling the API
        if (dryRun) {
            StringBuilder output = new StringBuilder();
            output.append("=== DRY RUN MODE ===\n\n");
            output.append("Model: ").append(model).append("\n\n");
            output.append("System Prompt:\n");
            output.append(SYSTEM_PROMPT).append("\n\n");
            output.append("User Prompt:\n");
            output.append(userPrompt).append("\n");
            output.append("\n=== END DRY RUN ===\n");
            return AnalyzerResult.ok(output.toString());
        }

        // Call LLM API
        try {
            String aiAnalysis = callLLM(model, userPrompt, rawOutput, showThinking);

            // If short mode, run through LLM again for succinct summary
            if (shortMode && !rawOutput) {
                aiAnalysis = createShortSummary(model, aiAnalysis, false, showThinking);
            }
            if (llmProvider.supportsStreaming()) {
                return AnalyzerResult.ok(""); // Output already printed during streaming
            }
            return AnalyzerResult.ok(aiAnalysis);

        } catch (LlmProvider.LlmException e) {
            if (e.isAuthError()) {
                return AnalyzerResult.withExitCode(
                    "Authentication failed: " + e.getMessage() + "\nPlease check your API key.",
                    4
                );
            } else {
                return AnalyzerResult.withExitCode(
                    "LLM error: " + e.getMessage(),
                    5
                );
            }
        } catch (IOException e) {
            return AnalyzerResult.withExitCode(
                "Network error: " + e.getMessage(),
                3
            );
        }
    }

    private String buildUserPrompt(String analysis, String customQuestion) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Thread Dump Analysis:\n");
        prompt.append("---\n");
        prompt.append(analysis);
        prompt.append("\n---\n\n");
        prompt.append(DEFAULT_USER_PROMPT);

        // Add provider-specific instructions
        String additionalInstructions = llmProvider.getAdditionalInstructions();
        if (!additionalInstructions.isEmpty()) {
            prompt.append("\nAdditional Instructions:\n");
            prompt.append(additionalInstructions);
            prompt.append("\n");
        }

        if (customQuestion != null && !customQuestion.trim().isEmpty()) {
            prompt.append("\n\nUser's specific question: ");
            prompt.append(customQuestion.trim());
        }

        return prompt.toString();
    }

    /**
     * Analyzes all JVMs on the system (full mode).
     *
     * @param count Number of dumps per JVM
     * @param intervalMs Interval between dumps in milliseconds
     * @param options Analysis options
     * @return Analyzer result with AI analysis of the entire system
     */
    public AnalyzerResult analyzeFullSystem(int count, long intervalMs, Map<String, Object> options) {
        // Extract AI-specific options
        String model = getStringOption(options, "model", "gpt-50-nano");
        String customQuestion = getStringOption(options, "question", null);
        boolean rawOutput = getBooleanOption(options, "raw", false);
        boolean dryRun = getBooleanOption(options, "dry-run", false);
        boolean shortMode = getBooleanOption(options, "short", false);
        boolean showThinking = getBooleanOption(options, "thinking", false);
        double cpuThreshold = getDoubleOption(options, "cpu-threshold", 1.0);

        // Enable intelligent filtering by default
        Map<String, Object> statusOptions = new HashMap<>(options);
        if (!statusOptions.containsKey("intelligent-filter")) {
            statusOptions.put("intelligent-filter", true);
        }

        // Analyze all JVMs
        SystemAnalyzer systemAnalyzer = new SystemAnalyzer();
        List<SystemAnalyzer.JVMAnalysis> analyses;
        try {
            analyses = systemAnalyzer.analyzeAllJVMs(count, intervalMs, statusOptions, cpuThreshold);
        } catch (IOException e) {
            return AnalyzerResult.withExitCode(
                "Failed to analyze JVMs: " + e.getMessage(),
                1
            );
        }

        if (analyses.isEmpty()) {
            return AnalyzerResult.ok("No active JVMs found (CPU threshold: " + cpuThreshold + "%)");
        }

        System.err.println("Found " + analyses.size() + " active JVM(s)");
        System.err.println();

        // Build combined analysis
        String systemSummary = SystemAnalyzer.formatSystemSummary(analyses);
        String detailedAnalyses = SystemAnalyzer.formatDetailedAnalyses(analyses);

        // Build user prompt with system context
        String userPrompt = buildFullSystemPrompt(systemSummary, detailedAnalyses, customQuestion);

        // Dry-run mode: just print the prompt without calling the API
        if (dryRun) {
            StringBuilder output = new StringBuilder();
            output.append("=== DRY RUN MODE (FULL SYSTEM) ===\n\n");
            output.append("Model: ").append(model).append("\n\n");
            output.append("System Prompt:\n");
            output.append(SYSTEM_PROMPT).append("\n\n");
            output.append("User Prompt:\n");
            output.append(userPrompt).append("\n");
            output.append("\n=== END DRY RUN ===\n");
            return AnalyzerResult.ok(output.toString());
        }

        // Call LLM API
        try {
            String aiAnalysis = callLLM(model, userPrompt, rawOutput, showThinking);

            // If short mode, run through LLM again for succinct summary
            if (shortMode && !rawOutput) {
                aiAnalysis = createShortSummary(model, aiAnalysis, true, showThinking);
            }

            return AnalyzerResult.ok(aiAnalysis);

        } catch (LlmProvider.LlmException e) {
            if (e.isAuthError()) {
                return AnalyzerResult.withExitCode(
                    "Authentication failed: " + e.getMessage() + "\nPlease check your API key.",
                    4
                );
            } else {
                return AnalyzerResult.withExitCode(
                    "LLM error: " + e.getMessage(),
                    5
                );
            }
        } catch (IOException e) {
            return AnalyzerResult.withExitCode(
                "Network error: " + e.getMessage(),
                3
            );
        }
    }

    private String buildFullSystemPrompt(String systemSummary, String detailedAnalyses, String customQuestion) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("System-wide Analysis of All JVM Applications:\n\n");
        prompt.append(systemSummary);
        prompt.append("\n\nDetailed Analysis of Each JVM:\n");
        prompt.append("---\n");
        prompt.append(detailedAnalyses);
        prompt.append("\n---\n\n");
        prompt.append("Please provide your analysis in the following structure:\n\n");
        prompt.append("1. Start with a high-level summary of the overall system state and bottom line takewaways\n");
        prompt.append("2. Identify any cross-JVM issues, bottlenecks, or interesting patterns\n");
        prompt.append("3. For each JVM provide a short analysis\n");
        prompt.append("Don't offer generic advice; focus on the specific findings from the analyses.\n");
        prompt.append("This is non-interactive; provide a complete answer based on the data provided. Omit calls for user input.\n");
        prompt.append("Give the JVMs nicer names, but omit sentences like 'Here is a concise, findings-focused analysis of the 5 active JVMs, with nicer names and non-generic guidance.'" +
                      " and omit trying to explain what any JVM is doing in general. Just use 'name (pid)'.\n");
        prompt.append("Don't use JVM1, ..., but 'name (pid)'.\n");
        prompt.append("Every fact needs to be based on the data provided; do not make up any facts or invent any findings" +
                      "and mention short reasons for every statement, be as specific as possible.\n");
        prompt.append("Be succinct and to the point, the user is focused on performance.\n");

        // Add provider-specific instructions
        String additionalInstructions = llmProvider.getAdditionalInstructions();
        if (!additionalInstructions.isEmpty()) {
            prompt.append("\nAdditional Instructions:\n");
            prompt.append(additionalInstructions);
            prompt.append("\n");
        }

        if (customQuestion != null && !customQuestion.trim().isEmpty()) {
            prompt.append("\n\nUser's specific question: ");
            prompt.append(customQuestion.trim());
        }

        return prompt.toString();
    }

    private double getDoubleOption(Map<String, Object> options, String key, double defaultValue) {
        Object value = options.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Calls the LLM with the given prompt and returns the response.
     */
    private String callLLM(String model, String userPrompt, boolean rawOutput, boolean showThinking)
            throws IOException, LlmProvider.LlmException {

        List<LlmProvider.Message> messages = new ArrayList<>();
        messages.add(new LlmProvider.Message("system", SYSTEM_PROMPT));
        messages.add(new LlmProvider.Message("user", userPrompt));

        if (rawOutput) {
            // Return raw response
            return llmProvider.getRawResponse(model, messages);
        } else {
            // Stream response
            StringBuilder output = new StringBuilder();

            // Warn if thinking mode is requested but provider doesn't support streaming
            if (showThinking && !llmProvider.supportsStreaming()) {
                System.err.println("Note: Thinking mode not supported by this provider (no streaming support)");
            }

            AtomicBoolean hadClosingThink = new AtomicBoolean(false);

            LlmProvider.StreamHandlers handlers = new LlmProvider.StreamHandlers(
                content -> {
                    if (hadClosingThink.get() || showThinking) {
                        output.append(content);
                        System.out.print(content);
                        System.out.flush();
                    }
                    if (content.contains("</think>")) {
                        content = content.substring(content.indexOf("</think>") + "</think>".length());
                        output.append(content);
                        System.out.print(content);
                        System.out.flush();
                        hadClosingThink.set(true);
                    }
                },
                showThinking && llmProvider.supportsStreaming() ? (thinkingToken -> {
                    System.err.print(thinkingToken);
                    System.err.flush();
                }) : null
            );

            llmProvider.chat(model, messages, handlers);
            System.out.println(); // Final newline
            return output.toString();
        }
    }

    /**
     * Creates a short summary of the analysis by running it through the LLM again.
     */
    private String createShortSummary(String model, String fullAnalysis, boolean isSystemMode, boolean showThinking)
            throws IOException, LlmProvider.LlmException {

        System.err.println("\n--- Creating short summary ---\n");

        String summaryPrompt;
        if (isSystemMode) {
            summaryPrompt = "Analyze the following system-wide analysis and provide a succinct summary of the current state of the system.\n" +
                    "Focus on the most important findings and issues. Be specific and data-driven.\n" +
                    "Keep it to 3-5 key points maximum. Don't give any advice, just state the state. Don't repeat yourself.\n\n" +
                    "Full Analysis:\n---\n" + fullAnalysis + "\n---";
        } else {
            summaryPrompt = "Analyze the following application analysis and provide a succinct summary of the current state of the application.\n" +
                    "Focus on the most important findings and issues. Be specific and data-driven.\n" +
                    "Keep it to 3-5 key points maximum. Don't give any advice, just state the state. Don't repeat yourself.\n\n" +
                    "Full Analysis:\n---\n" + fullAnalysis + "\n---";
        }

        List<LlmProvider.Message> messages = new ArrayList<>();
        messages.add(new LlmProvider.Message("system", SYSTEM_PROMPT));
        messages.add(new LlmProvider.Message("user", summaryPrompt));

        StringBuilder summary = new StringBuilder();

        LlmProvider.StreamHandlers handlers = new LlmProvider.StreamHandlers(
            content -> {
                summary.append(content);
                System.out.print(content);
                System.out.flush();
            },
            showThinking && llmProvider.supportsStreaming() ? (thinkingToken -> {
                System.err.print(thinkingToken);
                System.err.flush();
            }) : null
        );

        llmProvider.chat(model, messages, handlers);
        System.out.println(); // Final newline

        return summary.toString();
    }
}