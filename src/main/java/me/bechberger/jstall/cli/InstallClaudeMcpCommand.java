package me.bechberger.jstall.cli;

import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.util.json.JSONParser;
import me.bechberger.util.json.PrettyPrinter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers the jstall MCP server in ~/.claude/settings.json.
 */
@Command(
    name = "install-claude-mcp",
    description = "Register the jstall MCP server in ~/.claude/settings.json",
    hidden = true
)
public class InstallClaudeMcpCommand implements java.util.concurrent.Callable<Integer> {

    @Option(names = {"--force", "-f"}, description = "Overwrite existing jstall MCP entry without prompting")
    private boolean force;

    @Option(names = {"--settings"}, description = "Path to Claude settings.json (default: ~/.claude/settings.json)")
    private Path settingsPath;

    @Option(names = {"--jar"}, description = "Path to jstall.jar to embed in the MCP config (optional)")
    private Path jarPath;

    @Override
    @SuppressWarnings("unchecked")
    public Integer call() {
        Path settings = settingsPath != null
            ? settingsPath
            : Path.of(System.getProperty("user.home"), ".claude", "settings.json");

        // Parse existing settings or start fresh
        Map<String, Object> root;
        if (Files.exists(settings)) {
            try {
                Object parsed = JSONParser.parse(Files.readString(settings));
                if (!(parsed instanceof Map)) {
                    System.err.println(settings + " does not contain a JSON object.");
                    return 1;
                }
                root = (Map<String, Object>) parsed;
            } catch (IOException e) {
                System.err.println("Error reading " + settings + ": " + e.getMessage());
                return 1;
            }
        } else {
            root = new LinkedHashMap<>();
        }

        Map<String, Object> mcpServers = (Map<String, Object>) root.computeIfAbsent("mcpServers", k -> new LinkedHashMap<>());

        if (mcpServers.containsKey("jstall") && !force) {
            System.out.println("jstall MCP server already registered in " + settings);
            System.out.println("Use --force to overwrite.");
            return 0;
        }

        // Build the entry
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("command", "npx");
        entry.put("args", List.of("-y", "@bechberger/jstall-mcp"));
        if (jarPath != null) {
            entry.put("env", Map.of("JSTALL_JAR", jarPath.toAbsolutePath().toString()));
        }

        // Insert at the front so it's visible at the top of mcpServers
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("jstall", entry);
        mcpServers.forEach((k, v) -> { if (!k.equals("jstall")) reordered.put(k, v); });
        root.put("mcpServers", reordered);

        try {
            Files.createDirectories(settings.getParent());
            Files.writeString(settings, PrettyPrinter.prettyPrint(root) + "\n");
        } catch (IOException e) {
            System.err.println("Error writing " + settings + ": " + e.getMessage());
            return 1;
        }

        System.out.println("Registered jstall MCP server in " + settings);
        System.out.println("Restart Claude Code to activate.");
        return 0;
    }
}
