package me.bechberger.jstall.cli;

import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.jstall.Main;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Downloads and installs the jstall Claude Code skill to ~/.claude/skills/jstall/.
 */
@Command(
    name = "install-claude-skill",
    description = "Install the jstall Claude Code skill to ~/.claude/skills/jstall/",
    hidden = true
)
public class InstallClaudeSkillCommand implements java.util.concurrent.Callable<Integer> {

    private static final String BASE_URL =
        "https://raw.githubusercontent.com/parttimenerd/jstall/main/mcp/skills/jstall/";

    private static final String[] FILES = {
        "SKILL.md",
        "references/command-reference.md",
        "references/workflows.md",
    };

    @Option(names = {"--force", "-f"}, description = "Overwrite existing skill files without prompting")
    private boolean force;

    @Option(names = {"--skills-dir"}, description = "Claude skills directory (default: ~/.claude/skills)")
    private Path skillsDir;

    @Override
    public Integer call() {
        Path target = (skillsDir != null ? skillsDir : Path.of(System.getProperty("user.home"), ".claude", "skills"))
            .resolve("jstall");

        if (Files.exists(target) && !force) {
            System.out.println("Skill already installed at " + target);
            System.out.println("Use --force to overwrite.");
            return 0;
        }

        try {
            Files.createDirectories(target.resolve("references"));
        } catch (IOException e) {
            System.err.println("Error creating skill directory: " + e.getMessage());
            return 1;
        }

        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        System.out.println("Installing jstall Claude Code skill to " + target + " ...");

        for (String file : FILES) {
            String url = BASE_URL + file;
            Path dest = target.resolve(file);
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    System.err.println("Failed to download " + file + " (HTTP " + resp.statusCode() + ")");
                    return 1;
                }
                Files.writeString(dest, resp.body());
                System.out.println("  " + file);
            } catch (IOException | InterruptedException e) {
                System.err.println("Error downloading " + file + ": " + e.getMessage());
                return 1;
            }
        }

        System.out.println("Done. Reload Claude Code to activate the skill.");
        return 0;
    }
}
