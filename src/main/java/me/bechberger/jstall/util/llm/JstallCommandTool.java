package me.bechberger.jstall.util.llm;

import me.bechberger.jstall.Main;
import me.bechberger.femtocli.FemtoCli;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Exposes jstall commands as a single generic tool for local AI use.
 *
 * <p>Maintains three categories:
 * <ul>
 *   <li>Safe read-only commands — always allowed</li>
 *   <li>Mutating commands ({@code flame}, {@code record create}) — allowed only
 *       when {@code allowMutations=true}, and each invocation requires interactive
 *       confirmation on stderr/stdin (auto-denied when stdin is not a TTY).</li>
 *   <li>Hard-blocked commands ({@code ai}, {@code install-claude-*}) — never allowed.</li>
 * </ul>
 */
public class JstallCommandTool {

    private static final Set<String> SAFE_COMMANDS = Set.of(
        "list", "threads", "deadlock", "most-work", "waiting-threads",
        "dependency-graph", "dependency-tree", "gc-heap-info",
        "vm-metaspace", "vm-classloader-stats", "vm-vitals", "compiler-queue",
        "jvm-support", "processes", "status", "help",
        "record extract", "record summary"
    );

    private static final Set<String> MUTATING_COMMANDS = Set.of(
        "flame", "record create"
    );

    private static final Set<String> BLOCKED_COMMANDS = Set.of(
        "ai", "install-claude-mcp", "install-claude-skill", "install-claude-code-skill"
    );

    private final boolean allowMutations;
    private final List<String> defaultTargets;

    public JstallCommandTool(boolean allowMutations) {
        this(allowMutations, List.of());
    }

    public JstallCommandTool(boolean allowMutations, List<String> defaultTargets) {
        this.allowMutations = allowMutations;
        this.defaultTargets = defaultTargets == null ? List.of() : defaultTargets;
    }

    public ToolDefinition getToolDefinition() {
        String safeList = String.join(", ", SAFE_COMMANDS.stream().sorted().toList());
        String mutatingList = allowMutations
            ? String.join(", ", MUTATING_COMMANDS.stream().sorted().toList())
            : "(disabled — pass --allow-mutations to enable)";

        String targetHint = defaultTargets.isEmpty()
            ? "Include the target PID in args."
            : "Default target: " + String.join(" ", defaultTargets) + " (omit or specify another PID).";

        String description = ("Run a jstall diagnostic command on the target JVM. " +
            "Safe commands: " + safeList + ". " +
            "Useful flags: --stack-depth 0 (full stacks), --top N (limit rows). " +
            targetHint);

        return new ToolDefinition(
            "run_jstall_command",
            description,
            List.of(
                new ToolDefinition.Parameter("command", "string",
                    "The jstall command name, e.g. \"threads\", \"gc-heap-info\", \"flame\""),
                new ToolDefinition.Parameter("args", "string",
                    "Command arguments as a single string, e.g. \"--top 10 12345\" or empty", false)
            )
        );
    }

    /** Execute the tool call, returning the command output or an error message. */
    public String execute(ToolCall call) {
        String command = call.getString("command", "").trim();
        String args = call.getString("args", "").trim();

        if (command.isEmpty()) {
            return "Error: 'command' parameter is required.";
        }

        // Check blocklist (exact match or prefix for subcommands)
        for (String blocked : BLOCKED_COMMANDS) {
            if (command.equalsIgnoreCase(blocked) || command.toLowerCase().startsWith(blocked + " ")) {
                return "Error: command '" + command + "' is not available to the AI assistant.";
            }
        }

        // Check if mutating
        boolean isMutating = false;
        for (String mut : MUTATING_COMMANDS) {
            if (command.equalsIgnoreCase(mut) || command.toLowerCase().startsWith(mut + " ")) {
                isMutating = true;
                break;
            }
        }

        if (isMutating) {
            if (!allowMutations) {
                return "Error: command '" + command + "' has side effects. "
                    + "Run jstall with --allow-mutations to permit it.";
            }
            // Require interactive confirmation
            if (!confirmMutation(command)) {
                return "Denied by user: '" + command + "' was not confirmed.";
            }
        } else {
            // Verify it's in the safe set
            boolean isSafe = false;
            for (String safe : SAFE_COMMANDS) {
                if (command.equalsIgnoreCase(safe) || command.toLowerCase().startsWith(safe + " ")) {
                    isSafe = true;
                    break;
                }
            }
            if (!isSafe) {
                return "Error: command '" + command + "' is not in the allowed command set.";
            }
        }

        return runCommand(command, args);
    }

    private String runCommand(String command, String args) {
        // If the model didn't specify a target and we have a default, append it
        String effectiveArgs = args;
        if (!defaultTargets.isEmpty() && !hasTargetInArgs(args)) {
            String targetStr = String.join(" ", defaultTargets);
            effectiveArgs = args.isEmpty() ? targetStr : args + " " + targetStr;
        }

        // Build full args array: split command into parts, then args
        String fullArgs = (effectiveArgs.isEmpty() ? command : command + " " + effectiveArgs).trim();
        String[] argv = tokenize(fullArgs);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (PrintStream outPs = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream errPs = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            FemtoCli.run(new Main(), outPs, errPs, argv);
        }

        String output = out.toString(StandardCharsets.UTF_8);
        String errOutput = err.toString(StandardCharsets.UTF_8);

        if (output.length() > 8000) {
            output = output.substring(0, 8000) + "\n... (truncated)";
        }
        return output.isEmpty() && !errOutput.isEmpty()
            ? "[stderr]: " + errOutput.substring(0, Math.min(errOutput.length(), 2000))
            : output;
    }

    /**
     * Returns true if args already contains a numeric PID or a non-option word
     * (anything that doesn't start with '--' and is purely digits counts as a PID).
     */
    private boolean hasTargetInArgs(String args) {
        if (args == null || args.isBlank()) return false;
        for (String token : args.trim().split("\\s+")) {
            if (token.matches("\\d+")) return true;  // numeric PID
            if (!token.startsWith("-")) return true;  // non-flag word = filter/target
        }
        return false;
    }

    /**
     * Prompt the user on stderr/stdin for confirmation before a mutating command.
     * Auto-denies if stdin is not a TTY.
     */
    private boolean confirmMutation(String command) {
        Console console = System.console();
        if (console == null) {
            System.err.println("[ai] Auto-denying mutating command '" + command + "' (non-interactive session)");
            return false;
        }
        System.err.print("\n[ai] The AI wants to run: jstall " + command + "\nAllow? [y/N] ");
        System.err.flush();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String response = reader.readLine();
            return response != null && response.trim().equalsIgnoreCase("y");
        } catch (Exception e) {
            return false;
        }
    }

    /** Very simple argv tokeniser (handles quoted strings with single or double quotes). */
    static String[] tokenize(String s) {
        if (s == null || s.isBlank()) return new String[0];
        java.util.List<String> tokens = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char inQuote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote != 0) {
                if (c == inQuote) {
                    inQuote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                inQuote = c;
            } else if (c == ' ' || c == '\t') {
                if (!cur.isEmpty()) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) tokens.add(cur.toString());
        return tokens.toArray(new String[0]);
    }
}
