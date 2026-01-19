package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.impl.AiAnalyzer;
import me.bechberger.jstall.util.AnsweringMachineClient;
import me.bechberger.jstall.util.ApiKeyResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * AI-powered thread dump analysis using LLM.
 */
@Command(
    name = "ai",
    description = "AI-powered thread dump analysis using LLM",
    mixinStandardHelpOptions = true
)
public class AiCommand extends BaseAnalyzerCommand {

    @Option(names = "--model", description = "LLM model to use (default: gpt-50-nano)")
    private String model = "gpt-50-nano";

    @Option(names = "--question", description = "Custom question to ask (use '-' to read from stdin)")
    private String question;

    @Option(names = "--raw", description = "Output raw JSON response")
    private boolean raw = false;

    // Status options
    @Option(names = "--top", description = "Number of top threads (default: 3)")
    private int top = 3;

    @Option(names = "--no-native", description = "Ignore threads without stack traces")
    private boolean noNative = false;

    @Option(names = "--stack-depth", description = "Stack trace depth (default: 10, 0=all)")
    private int stackDepth = 10;

    @Option(names = "--dry-run", description = "Perform a dry run without calling the AI API")
    private boolean dryRun;

    private Analyzer analyzer;

    @Override
    protected Analyzer getAnalyzer() {
        if (analyzer == null) {
            // Resolve API key
            String apiKey;
            try {
                apiKey = ApiKeyResolver.resolve();
            } catch (ApiKeyResolver.ApiKeyNotFoundException e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(2);
                return null; // unreachable
            }

            // Create analyzer
            AnsweringMachineClient client = new AnsweringMachineClient();
            analyzer = new AiAnalyzer(client, apiKey);
        }
        return analyzer;
    }

    @Override
    protected Map<String, Object> getAdditionalOptions() {
        Map<String, Object> options = new HashMap<>();

        // AI-specific options
        options.put("model", model);
        options.put("raw", raw);
        options.put("dry-run", dryRun);

        // Handle question (with stdin support)
        if (question != null) {
            if ("-".equals(question)) {
                // Read from stdin
                try {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(System.in));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    options.put("question", sb.toString().trim());
                } catch (IOException e) {
                    System.err.println("Error reading question from stdin: " + e.getMessage());
                    System.exit(1);
                }
            } else {
                options.put("question", question);
            }
        }

        // Status options
        options.put("top", top);
        options.put("no-native", noNative);
        options.put("stack-depth", stackDepth);

        // Enable intelligent-filter by default for AI command if not explicitly set
        if (intelligentFilter == null) {
            options.put("intelligent-filter", true);
        }

        return options;
    }
}