package me.bechberger.jstall.cli;

import me.bechberger.jstall.Main;
import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.RunResult;
import me.bechberger.jstall.util.CommandExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static me.bechberger.jstall.testframework.TestAppLauncher.getJavaExecutable;

/**
 * Configurable utility for running commands.
 * <p>
 * Configuration options:
 * <dl>
 *     <dt>test.externalJar=path/to/external.jar</dt>
 *     <dd>test running commands from an external jar</dd>
 *     <dt>test.forceRunWithShell=[true|false]</dt>
 *     <dd>force running commands with "-s LOCAL_SHELL" to test remote calling.</dd>
 * </dl>
 */
public class RunCommandUtil {
    private RunCommandUtil() {
        // utility class
    }

    private static final String externalJar;

    static {
        if (System.getProperty("test.externalJar") != null) {
            externalJar = System.getProperty("test.externalJar");
            if ( externalJar.isBlank() || !Objects.requireNonNull(externalJar).endsWith(".jar") || !Files.exists(java.nio.file.Path.of(externalJar))) {
                throw new IllegalArgumentException("System property 'test.externalJar' is blank");
            }
        } else {
            externalJar = null;
        }
    }

    /**
     * Doesn't use the configured external jar, even if specified. Use this to test running commands from the current classpath.
     */
    static RunResult run(Object command, String... args) {
        return FemtoCli.builder().commandConfig(Main::setFemtoCliCommandConfig).runCaptured(command, args);
    }

    /** External JAR capable */
    public static RunResultAssert run(String... args) {
        List<String> command = buildCommand(externalJar, args);
        return executeCommand(command, "Running command");
    }

    /**
     * Execute a command list, handling both external JAR and classpath modes.
     */
    private static RunResultAssert executeCommand(List<String> command, String description) {
        if (externalJar != null) {
            CommandExecutor.LocalCommandExecutor executor = new CommandExecutor.LocalCommandExecutor();
            try {
                return new RunResultAssert(executor.executeCommand(getJavaExecutable(), command.toArray(new String[0])).toRunResult());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        System.out.println(description + ": " + String.join(" ", command));
        return new RunResultAssert(FemtoCli.builder().commandConfig(Main::setFemtoCliCommandConfig).runCaptured(new Main(), command.toArray(new String[0])));
    }

    /**
     * Build the command list, optionally wrapping it with shell execution using -s flag.
     * @param externalJarPath path to external JAR (null for classpath mode)
     * @param args command arguments
     * @return command list to execute
     */
    private static List<String> buildCommand(String externalJarPath, String... args) {
        List<String> command = new ArrayList<>();

        if (externalJarPath != null) {
            command.add("-jar");
            command.add(externalJarPath);
        }

        if (forceShellOption()) {
            // FemtoCli parses help/version reliably when they come before -s.
            if (isGlobalHelpOrVersion(args)) {
                command.addAll(List.of(args));
            }
            command.add("-s");
            command.add(getShellPrefix());
            if (!isGlobalHelpOrVersion(args)) {
                command.addAll(List.of(args));
            }
        } else {
            command.addAll(List.of(args));
        }

        return command;
    }

    private static boolean isGlobalHelpOrVersion(String[] args) {
        return args.length == 1
            && ("--help".equals(args[0]) || "-h".equals(args[0]) || "--version".equals(args[0]) || "-V".equals(args[0]));
    }

    /**
     * Get the shell prefix based on OS.
     */
    private static String getShellPrefix() {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return "cmd.exe /c";
        } else {
            return "/bin/sh -c";
        }
    }

    private static boolean forceShellOption() {
        return Boolean.parseBoolean(System.getProperty("test.forceRunWithShell", "false"));
    }

    /**
     * Run command with "-s LOCAL_SHELL" to test shell execution.
     * This explicitly uses shell execution via -s flag regardless of forceShellOption.
     * <p/>
     * External JAR capable
     */
    public static RunResultAssert runWithShell(String... args) {
        List<String> command = new ArrayList<>();
        if (externalJar != null) {
            command.add("-jar");
            command.add(externalJar);
        }
        // Always add shell option with shell prefix as a separate value.
        command.add("-s");
        command.add(getShellPrefix());
        command.addAll(List.of(args));
        return executeCommand(command, "Running command with shell");
    }
}