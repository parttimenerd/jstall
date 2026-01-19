package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.BaseAnalyzer;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.model.ThreadDumpWithRaw;
import me.bechberger.jstall.util.AnsweringMachineClient;
import me.bechberger.jthreaddump.model.ThreadDump;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI-powered thread dump analyzer using LLM.
 *
 * Runs StatusAnalyzer to get thread dump analysis, then sends it to an LLM
 * for intelligent insights and answers.
 */
public class AiAnalyzer extends BaseAnalyzer {

    private static final String SYSTEM_PROMPT =
        "You're a helpful thread dump analyzer. Given the following thread dump analysis, answer the user's question.";

    private static final String DEFAULT_USER_PROMPT =
        "Summarize the current state of the application. State any potential issues found in the thread dumps. But start with a short summary of the overall state.\n";

    private final AnsweringMachineClient client;
    private final String apiKey;

    /**
     * Creates an AI analyzer with the specified API key.
     *
     * @param client The answering machine client
     * @param apiKey API key for authentication
     */
    public AiAnalyzer(AnsweringMachineClient client, String apiKey) {
        this.client = client;
        this.apiKey = apiKey;
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
        return options;
    }

    @Override
    public DumpRequirement dumpRequirement() {
        return DumpRequirement.MANY;
    }

    @Override
    public AnalyzerResult analyze(List<ThreadDumpWithRaw> dumps, Map<String, Object> options) {
        // Extract AI-specific options
        String model = getStringOption(options, "model", "gpt-50-nano");
        String customQuestion = getStringOption(options, "question", null);
        boolean rawOutput = getBooleanOption(options, "raw", false);
        boolean dryRun = getBooleanOption(options, "dry-run", false);

        // Enable intelligent filtering by default
        Map<String, Object> statusOptions = new HashMap<>(options);
        if (!statusOptions.containsKey("intelligent-filter")) {
            statusOptions.put("intelligent-filter", true);
        }

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
            List<AnsweringMachineClient.Message> messages = new ArrayList<>();
            messages.add(new AnsweringMachineClient.Message("system", SYSTEM_PROMPT));
            messages.add(new AnsweringMachineClient.Message("user", userPrompt));

            if (rawOutput) {
                // Return raw JSON
                String rawResponse = client.getCompletionRaw(apiKey, model, messages);
                return AnalyzerResult.ok(rawResponse);
            } else {
                // Stream response
                StringBuilder output = new StringBuilder();
                client.streamCompletion(apiKey, model, messages, content -> {
                    output.append(content);
                    // Print to stdout immediately for streaming effect
                    System.out.print(content);
                    System.out.flush();
                });
                System.out.println(); // Final newline

                return AnalyzerResult.ok(output.toString());
            }

        } catch (AnsweringMachineClient.ApiException e) {
            if (e.isAuthError()) {
                return AnalyzerResult.withExitCode(
                    "Authentication failed: " + e.getMessage() + "\nPlease check your API key.",
                    4
                );
            } else {
                return AnalyzerResult.withExitCode(
                    "API error: " + e.getMessage(),
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

        if (customQuestion != null && !customQuestion.trim().isEmpty()) {
            prompt.append("\n\nUser's specific question: ");
            prompt.append(customQuestion.trim());
        }

        return prompt.toString();
    }
}