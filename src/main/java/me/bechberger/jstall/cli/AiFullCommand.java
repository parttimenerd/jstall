package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.impl.AiAnalyzer;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.jstall.util.llm.AiConfig;
import me.bechberger.jstall.util.llm.LlmProvider;
import me.bechberger.jstall.util.llm.LlmProviderFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * AI-powered analysis of all JVMs on the system.
 */
@Command(
    name = "full",
    description = "Analyze all JVMs on the system with AI"
)
public class AiFullCommand implements Callable<Integer> {

    @Option(names = "--local", description = "Use local Ollama provider (overrides config)")
    private boolean useLocal;

    @Option(names = "--remote", description = "Use remote Gardener AI provider (overrides config)")
    private boolean useRemote;

    @Option(names = "--model", description = "LLM model to use (default from config or provider default)")
    private   String model;

    @Option(names = "--question", description = "Custom question to ask (use '-' to read from stdin)")
    private String question;

    @Option(names = "--raw", description = "Output raw JSON response")
    private boolean raw = false;

    @Option(names = "--cpu-threshold", description = "CPU threshold percentage (default: ${DEFAULT-VALUE}%)")
    private double cpuThreshold = 1.0;

    @Option(names = {"-n", "--dumps"}, description = "Number of dumps per JVM (default: ${DEFAULT-VALUE})")
    private int dumps = 2;

    @Option(names = {"-i", "--interval"}, description = "Interval between dumps in seconds (default: ${DEFAULT-VALUE})")
    private double interval = 1.0;

    // Status options
    @Option(names = "--top", description = "Number of top threads per JVM (default: ${DEFAULT-VALUE})")
    private int top = 3;

    @Option(names = "--no-native", description = "Ignore threads without stack traces")
    private boolean noNative = false;

    @Option(names = "--stack-depth", description = "Stack trace depth (default: ${DEFAULT-VALUE}, 0=all)")
    private int stackDepth = 10;

    @Option(names = "--dry-run", description = "Perform a dry run without calling the AI API")
    private boolean dryRun;

    @Option(names = "--short", description = "Create a succinct summary of the system analysis")
    private boolean shortMode;

    @Option(names = "--thinking", description = "Show thinking tokens (Ollama only)")
    private boolean showThinking;

    @Override
    public Integer call() {
        try {
            LlmProviderFactory.Selection selection = LlmProviderFactory.create(useLocal, useRemote, model);
            LlmProvider llmProvider = selection.provider();
            model = selection.model();

            // Create analyzer
            AiAnalyzer analyzer = new AiAnalyzer(llmProvider);

            // Build options
            Map<String, Object> options = buildOptions();

            // Run full system analysis
            long intervalMs = (long) (interval * 1000);
            AnalyzerResult result = analyzer.analyzeFullSystem(dumps, intervalMs, options);

            // Print output if not already printed by streaming
            if (raw || dryRun) {
                System.out.println(result.output());
            }

            return result.exitCode();

        } catch (AiConfig.ConfigNotFoundException | IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }
    }

    private Map<String, Object> buildOptions() {
        Map<String, Object> options = new HashMap<>();

        // AI-specific options
        options.put("model", model);
        options.put("raw", raw);
        options.put("dry-run", dryRun);
        options.put("cpu-threshold", cpuThreshold);
        options.put("short", shortMode);
        options.put("thinking", showThinking);

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

        // Enable intelligent-filter by default
        options.put("intelligent-filter", true);

        return options;
    }
}