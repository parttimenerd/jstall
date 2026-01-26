package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.impl.AiAnalyzer;
import me.bechberger.minicli.annotations.Command;
import me.bechberger.minicli.annotations.Option;
import me.bechberger.jstall.util.llm.AiConfig;
import me.bechberger.jstall.util.llm.LlmProvider;
import me.bechberger.jstall.util.llm.LlmProviderFactory;

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
    subcommands = {
        AiFullCommand.class
    }
)
public class AiCommand extends BaseAnalyzerCommand {

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

    // Status options
    @Option(names = "--top", description = "Number of top threads (default: ${DEFAULT-VALUE})")
    private int top = 3;

    @Option(names = "--no-native", description = "Ignore threads without stack traces")
    private boolean noNative = false;

    @Option(names = "--stack-depth", description = "Stack trace depth (default: ${DEFAULT-VALUE}, 0=all)")
    private int stackDepth = 10;

    @Option(names = "--dry-run", description = "Perform a dry run without calling the AI API")
    private boolean dryRun;

    @Option(names = "--short", description = "Create a succinct summary of the analysis")
    private boolean shortMode;

    @Option(names = "--thinking", description = "Show thinking tokens (Ollama only)")
    private boolean showThinking;

    private Analyzer analyzer;

    @Override
    protected Analyzer getAnalyzer() {
        if (analyzer == null) {
            try {
                LlmProviderFactory.Selection selection = LlmProviderFactory.create(useLocal, useRemote, model);
                LlmProvider llmProvider = selection.provider();
                model = selection.model();
                analyzer = new AiAnalyzer(llmProvider);
            } catch (AiConfig.ConfigNotFoundException | IllegalArgumentException e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(2);
                return null;
            }
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

        // Enable intelligent-filter by default for AI command if not explicitly set
        if (intelligentFilter == null) {
            options.put("intelligent-filter", true);
        }

        return options;
    }
}