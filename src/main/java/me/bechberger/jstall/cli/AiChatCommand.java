package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.impl.AiAnalyzer;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.jstall.Main;
import me.bechberger.jstall.util.llm.AiConfig;
import me.bechberger.jstall.util.llm.LlmProvider;

import java.util.HashMap;
import java.util.Map;

/**
 * Interactive chat REPL after an initial AI analysis.
 *
 * <p>Equivalent to the previous {@code ai --chat} flag, promoted to a subcommand
 * for discoverability. Local provider only (the chat loop relies on tool support).
 */
@Command(
    name = "chat",
    description = "Run an analysis then drop into an interactive chat (local provider only)"
)
public class AiChatCommand extends BaseAnalyzerCommand {

    @Option(names = {"-p", "--provider"},
        description = "LLM provider: auto, local, remote",
        defaultValue = "local")
    private String provider = "local";

    @Option(names = {"-m", "--model"},
        description = "LLM model to use (default from config or provider default)")
    private String model;

    @Option(names = "--base-url",
        description = "Base URL for the LLM API (overrides config). Implies --provider local.")
    private String baseUrl;

    @Option(names = {"-q", "--question"},
        description = "Initial question (otherwise piped stdin or the default analysis prompt)")
    private String question;

    @Option(names = "--top",
        description = "Number of top threads",
        defaultValue = "3")
    private int top = 3;

    @Option(names = "--no-native", description = "Ignore threads without stack traces")
    private boolean noNative;

    @Option(names = "--stack-depth",
        description = "Stack trace depth (0=all)",
        defaultValue = "10")
    private int stackDepth = 10;

    @Option(names = "--reasoning",
        description = "Show the model's pre-tool reasoning and <think> blocks")
    private boolean reasoning;

    @Option(names = "--src",
        description = "Project source root for AI file exploration. Auto-detected from git if omitted")
    private String src;

    @Option(names = "--no-pretty", description = "Disable markdown rendering")
    private boolean noPretty;

    @Option(names = "--no-mutations",
        description = "Disable side-effecting commands (flame, record create). Mutations are on by default in chat with per-call y/N confirmation")
    private boolean noMutations;

    @Option(names = "--quiet",
        description = "Suppress progress output")
    private boolean quiet;

    private Analyzer analyzer;

    @Override
    protected Analyzer getAnalyzer() {
        if (analyzer == null) {
            try {
                ProviderResolver.ResolvedProvider resolved =
                    ProviderResolver.resolve(provider, model, baseUrl);
                LlmProvider llmProvider = resolved.provider();
                model = resolved.model();
                analyzer = new AiAnalyzer(llmProvider, spec.getParent(Main.class).executor());
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
        options.put("model", model);
        options.put("think", reasoning);
        options.put("chat", true);

        String resolvedSrc = AiCommand.resolveSourceRoot(src);
        if (resolvedSrc != null) {
            options.put("source-root", resolvedSrc);
        }
        if (noPretty) {
            options.put("no-pretty", true);
        }
        if (!noMutations) {
            options.put("allow-mutations", true);
        }
        boolean verbose = !quiet && System.console() != null;
        if (verbose) {
            options.put("verbose", true);
        }
        if (targets != null && !targets.isEmpty()) {
            options.put("targets", targets);
        }
        String resolvedQuestion = AiCommand.resolveQuestion(question);
        if (resolvedQuestion != null) {
            options.put("question", resolvedQuestion);
        }
        options.put("top", getTop(top));
        options.put("no-native", noNative);
        options.put("stack-depth", stackDepth);
        if (intelligentFilter == null) {
            options.put("intelligent-filter", true);
        }
        return options;
    }

    @Override
    protected boolean supportsMultipleTargets() {
        return false;
    }
}
