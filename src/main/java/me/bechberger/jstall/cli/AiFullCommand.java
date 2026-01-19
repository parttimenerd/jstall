package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.AnalyzerResult;
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
import java.util.concurrent.Callable;

/**
 * AI-powered analysis of all JVMs on the system.
 */
@Command(
    name = "full",
    description = "Analyze all JVMs on the system with AI",
    mixinStandardHelpOptions = true
)
public class AiFullCommand implements Callable<Integer> {

    @Option(names = "--model", description = "LLM model to use (default: gpt-50-nano)")
    private String model = "gpt-50-nano";

    @Option(names = "--question", description = "Custom question to ask (use '-' to read from stdin)")
    private String question;

    @Option(names = "--raw", description = "Output raw JSON response")
    private boolean raw = false;

    @Option(names = "--cpu-threshold", description = "CPU threshold percentage (default: 1.0%)")
    private double cpuThreshold = 1.0;

    @Option(names = {"-n", "--dumps"}, description = "Number of dumps per JVM (default: 2)")
    private int dumps = 2;

    @Option(names = {"-i", "--interval"}, description = "Interval between dumps in seconds (default: 1)")
    private double interval = 1.0;

    // Status options
    @Option(names = "--top", description = "Number of top threads per JVM (default: 3)")
    private int top = 3;

    @Option(names = "--no-native", description = "Ignore threads without stack traces")
    private boolean noNative = false;

    @Option(names = "--stack-depth", description = "Stack trace depth (default: 10, 0=all)")
    private int stackDepth = 10;

    @Option(names = "--dry-run", description = "Perform a dry run without calling the AI API")
    private boolean dryRun;

    @Option(names = "--short", description = "Create a succinct summary of the system analysis")
    private boolean shortMode;

    @Option(names = "--intelligent-filter", description = "Enable intelligent stack filtering (default: true)")
    private Boolean intelligentFilter;

    @Override
    public Integer call() {
        // Resolve API key
        String apiKey;
        try {
            apiKey = ApiKeyResolver.resolve();
        } catch (ApiKeyResolver.ApiKeyNotFoundException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }

        // Create analyzer
        AnsweringMachineClient client = new AnsweringMachineClient();
        AiAnalyzer analyzer = new AiAnalyzer(client, apiKey);

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
    }

    private Map<String, Object> buildOptions() {
        Map<String, Object> options = new HashMap<>();

        // AI-specific options
        options.put("model", model);
        options.put("raw", raw);
        options.put("dry-run", dryRun);
        options.put("short", shortMode);
        options.put("cpu-threshold", cpuThreshold);

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
        } else {
            options.put("intelligent-filter", intelligentFilter);
        }

        return options;
    }
}