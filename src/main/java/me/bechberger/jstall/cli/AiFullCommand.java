package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.impl.AiAnalyzer;
import me.bechberger.jstall.util.AiConfig;
import me.bechberger.jstall.util.GardenerLlmProvider;
import me.bechberger.jstall.util.LlmProvider;
import me.bechberger.jstall.util.OllamaLlmProvider;
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

    @Option(names = "--local", description = "Use local Ollama provider (overrides config)")
    private boolean useLocal;

    @Option(names = "--remote", description = "Use remote Gardener AI provider (overrides config)")
    private boolean useRemote;

    @Option(names = "--model", description = "LLM model to use (default from config or provider default)")
    private String model;

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

    @Option(names = "--thinking", description = "Show thinking tokens (Ollama only)")
    private boolean showThinking;

    @Override
    public Integer call() {
        // Check for conflicting options
        if (useLocal && useRemote) {
            System.err.println("Error: Cannot use both --local and --remote options");
            return 2;
        }

        // Load AI configuration
        AiConfig config;
        try {
            config = AiConfig.load();
        } catch (AiConfig.ConfigNotFoundException e) {
            // If no config found and no override specified, show error
            if (!useLocal && !useRemote) {
                System.err.println("Error: " + e.getMessage());
                return 2;
            }
            // Use defaults when overriding
            config = null;
        }

        // Determine which provider to use
        boolean useOllama;
        if (useLocal) {
            useOllama = true;
        } else if (useRemote) {
            useOllama = false;
        } else {
            // Use config setting
            useOllama = config != null && config.isOllama();
        }

        // Create appropriate LLM provider
        LlmProvider llmProvider;
        if (useOllama) {
            String host = (config != null && config.getOllamaHost() != null)
                ? config.getOllamaHost()
                : "http://127.0.0.1:11434";
            llmProvider = new OllamaLlmProvider(host);
        } else {
            // Gardener AI - need API key
            String key = (config != null) ? config.getApiKey() : null;
            if (key == null) {
                // Try environment variable as fallback
                key = System.getenv("ANSWERING_MACHINE_APIKEY");
            }
            if (key == null) {
                System.err.println("Error: API key required for Gardener AI provider");
                System.err.println("Set ANSWERING_MACHINE_APIKEY environment variable or configure in .jstall-ai-config");
                return 2;
            }
            llmProvider = new GardenerLlmProvider(key);
        }

        // Determine model to use
        if (model == null) {
            // If provider was overridden, use provider-specific default
            if (useLocal || useRemote) {
                model = useOllama ? "qwen3:30b" : "gpt-50-nano";
            } else if (config != null && config.getModel() != null) {
                // Use config model
                model = config.getModel();
            } else {
                // Use provider defaults based on config
                model = useOllama ? "qwen3:30b" : "gpt-50-nano";
            }
        }

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