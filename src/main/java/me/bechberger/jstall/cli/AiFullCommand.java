package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.impl.AiAnalyzer;
import me.bechberger.femtocli.Spec;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.jstall.Main;
import me.bechberger.jstall.util.llm.AiConfig;
import me.bechberger.jstall.util.llm.LlmProvider;

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

    @Option(names = {"-p", "--provider"},
        description = "LLM provider: auto, local, remote",
        defaultValue = "auto")
    private String provider = "auto";

    @Option(names = {"-m", "--model"},
        description = "LLM model to use (default from config or provider default)")
    private String model;

    @Option(names = "--base-url",
        description = "Base URL for the LLM API (overrides config). Implies --provider local.")
    private String baseUrl;

    @Option(names = {"-q", "--question"},
        description = "Custom question to ask. If omitted and stdin is piped, the piped text is used")
    private String question;

    @Option(names = "--raw", description = "Output raw JSON response")
    private boolean raw;

    @Option(names = "--cpu-threshold",
        description = "CPU threshold percentage",
        defaultValue = "1.0")
    private double cpuThreshold = 1.0;

    @Option(names = {"-n", "--dump-count"},
        description = "Number of dumps per JVM",
        defaultValue = "2")
    private int count = 2;

    @Option(names = {"-i", "--interval"},
        description = "Interval between dumps in seconds",
        defaultValue = "1.0")
    private double interval = 1.0;

    @Option(names = "--top",
        description = "Number of top threads per JVM",
        defaultValue = "3")
    private int top = 3;

    @Option(names = "--no-native", description = "Ignore threads without stack traces")
    private boolean noNative;

    @Option(names = "--stack-depth",
        description = "Stack trace depth (0=all)",
        defaultValue = "10")
    private int stackDepth = 10;

    @Option(names = "--dry-run", description = "Print the full prompt without calling the AI API")
    private boolean dryRun;

    @Option(names = "--short", description = "Create a succinct summary of the analysis")
    private boolean shortMode;

    @Option(names = "--reasoning",
        description = "Show the model's pre-tool reasoning and <think> blocks (local provider only)")
    private boolean reasoning;

    @Option(names = "--no-pretty", description = "Disable markdown rendering")
    private boolean noPretty;

    @Option(names = "--quiet",
        description = "Suppress progress output")
    private boolean quiet;

    Spec spec;

    @Override
    public Integer call() {
        try {
            ProviderResolver.ResolvedProvider resolved =
                ProviderResolver.resolve(provider, model, baseUrl);
            LlmProvider llmProvider = resolved.provider();
            model = resolved.model();

            AiAnalyzer analyzer = new AiAnalyzer(llmProvider, spec.getParent(Main.class).executor());

            Map<String, Object> options = buildOptions();

            long intervalMs = (long) (interval * 1000);
            AnalyzerResult result = analyzer.analyzeFullSystem(count, intervalMs, options);

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

        options.put("model", model);
        options.put("raw", raw);
        options.put("dry-run", dryRun);
        options.put("cpu-threshold", cpuThreshold);
        options.put("short", shortMode);
        options.put("think", reasoning);

        if (noPretty) {
            options.put("no-pretty", true);
        }
        boolean verbose = !quiet && System.console() != null;
        if (verbose) {
            options.put("verbose", true);
        }

        String resolvedQuestion = AiCommand.resolveQuestion(question);
        if (resolvedQuestion != null) {
            options.put("question", resolvedQuestion);
        }

        options.put("top", BaseAnalyzerCommand.getTop(top));
        options.put("no-native", noNative);
        options.put("stack-depth", stackDepth);

        options.put("intelligent-filter", true);

        return options;
    }
}
