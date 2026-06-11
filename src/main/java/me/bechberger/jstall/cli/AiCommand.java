package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.impl.AiAnalyzer;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.jstall.Main;
import me.bechberger.jstall.util.llm.AiConfig;
import me.bechberger.jstall.util.llm.LlmProvider;
import me.bechberger.jstall.util.llm.LlmProviderFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * AI-powered thread dump analysis using LLM.
 */
@Command(
    name = "ai",
    description = "AI-powered thread dump analysis using LLM",
    subcommands = {
        AiFullCommand.class,
        AiChatCommand.class
    }
)
public class AiCommand extends BaseAnalyzerCommand {

    @Option(names = {"-p", "--provider"},
        description = "LLM provider: auto (default, from config), local, remote",
        defaultValue = "auto")
    private String provider = "auto";

    @Option(names = {"-m", "--model"},
        description = "LLM model to use (default from config or provider default)")
    private String model;

    @Option(names = "--base-url",
        description = "Base URL for the LLM API (overrides config). Implies --provider local. Useful when running multiple llama-server instances on different ports.")
    private String baseUrl;

    @Option(names = {"-q", "--question"},
        description = "Custom question to ask. If omitted and stdin is piped, the piped text is used as the question")
    private String question;

    @Option(names = "--raw", description = "Output raw JSON response")
    private boolean raw;

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

    @Option(names = "--dry-run", description = "Print the full prompt without calling the AI API")
    private boolean dryRun;

    @Option(names = "--short", description = "Create a succinct summary of the analysis")
    private boolean shortMode;

    @Option(names = "--reasoning",
        description = "Show the model's pre-tool reasoning and <think> blocks (local provider only)")
    private boolean reasoning;

    @Option(names = "--tools",
        description = "Enable LLM tool calling: on (default), off",
        defaultValue = "on")
    private String tools = "on";

    @Option(names = "--src",
        description = "Project source root for AI file exploration. Auto-detected from git if omitted; pass an empty string to disable")
    private String src;

    @Option(names = "--no-pretty", description = "Disable markdown rendering (default: on when connected to a terminal)")
    private boolean noPretty;

    @Option(names = "--allow-mutations",
        description = "Allow AI to invoke side-effecting commands (flame, record create) with confirmation")
    private boolean allowMutations;

    @Option(names = "--quiet",
        description = "Suppress progress output (progress is on by default when connected to a terminal)")
    private boolean quiet;

    @Option(names = "--save",
        description = "Save the final analysis to a file")
    private String saveTo;

    private Analyzer analyzer;

    @Override
    protected Analyzer getAnalyzer() {
        if (analyzer == null) {
            // Validate tri-state options up-front, before initializing the provider
            // (which may launch a local llama-server — bad UX on a typo).
            validateToolsOption(tools);
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

    static void validateToolsOption(String tools) {
        if (tools == null) return;
        switch (tools.toLowerCase()) {
            case "on", "off" -> {}
            default -> {
                System.err.println("Error: --tools must be one of: on, off (got '" + tools + "')");
                System.exit(2);
            }
        }
    }

    @Override
    protected Map<String, Object> getAdditionalOptions() {
        Map<String, Object> options = new HashMap<>();

        // AI-specific options
        options.put("model", model);
        options.put("raw", raw);
        options.put("dry-run", dryRun);
        options.put("short", shortMode);
        options.put("think", reasoning);

        // Translate --tools on|off into the analyzer's no-tools/tools flags.
        // (already validated in getAnalyzer)
        switch (tools.toLowerCase()) {
            case "off" -> options.put("no-tools", true);
            case "on" -> options.put("tools", true);
        }

        String resolvedSrc = resolveSourceRoot(src);
        if (resolvedSrc != null) {
            options.put("source-root", resolvedSrc);
        }
        if (noPretty) {
            options.put("no-pretty", true);
        }
        if (allowMutations) {
            options.put("allow-mutations", true);
        }
        // Verbose is on by default for TTY; --quiet flips it off
        boolean verbose = !quiet && System.console() != null;
        if (verbose) {
            options.put("verbose", true);
        }
        if (saveTo != null && !saveTo.isBlank()) {
            options.put("save", saveTo);
        }

        // Pass the analysis target(s) so tools like run_jstall_command can use the correct PID
        if (targets != null && !targets.isEmpty()) {
            options.put("targets", targets);
        }

        // Question handling: explicit --question takes precedence; otherwise auto-detect piped stdin
        String resolvedQuestion = resolveQuestion(question);
        if (resolvedQuestion != null) {
            options.put("question", resolvedQuestion);
        }

        // Status options
        options.put("top", getTop(top));
        options.put("no-native", noNative);
        options.put("stack-depth", stackDepth);

        // Enable intelligent-filter by default for AI command if not explicitly set
        if (intelligentFilter == null) {
            options.put("intelligent-filter", true);
        }

        return options;
    }

    /**
     * Resolves the question: explicit --question wins; otherwise read piped stdin
     * (only when stdin is not a TTY, so interactive use isn't blocked).
     */
    static String resolveQuestion(String questionOpt) {
        if (questionOpt != null && !questionOpt.equals("-")) {
            return questionOpt.trim().isEmpty() ? null : questionOpt;
        }
        boolean explicitStdin = "-".equals(questionOpt);
        boolean hasPipedStdin = System.console() == null;
        if (!explicitStdin && !hasPipedStdin) {
            return null;
        }
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            String result = sb.toString().trim();
            return result.isEmpty() ? null : result;
        } catch (IOException e) {
            System.err.println("Error reading question from stdin: " + e.getMessage());
            return null;
        }
    }

    /**
     * Resolves the source root: explicit --src wins (empty string disables);
     * otherwise auto-detect from `git rev-parse --show-toplevel`.
     */
    static String resolveSourceRoot(String srcOpt) {
        if (srcOpt != null) {
            return srcOpt.isBlank() ? null : srcOpt;
        }
        // Auto-detect from current working directory's git root
        Path cwd = Path.of(System.getProperty("user.dir", "."));
        Path candidate = cwd;
        for (int i = 0; i < 10 && candidate != null; i++) {
            if (Files.isDirectory(candidate.resolve(".git"))) {
                return candidate.toString();
            }
            candidate = candidate.getParent();
        }
        // Fall back to cwd so source tools still work
        return cwd.toString();
    }
}
