package me.bechberger.jstall;

import me.bechberger.jstall.cli.*;
import me.bechberger.minicli.CommandConfig;
import me.bechberger.minicli.MiniCli;
import me.bechberger.minicli.annotations.Command;
import me.bechberger.jstall.util.JVMDiscovery;

/**
 * Main entry point for JStall.
 */
@Command(
    name = "jstall",
    description = "One-shot JVM inspection tool",
    version = "0.4.9",
    subcommands = {
        StatusCommand.class,
        DeadLockCommand.class,
        MostWorkCommand.class,
        FlameCommand.class,
        ThreadsCommand.class,
        WaitingThreadsCommand.class,
        DependencyGraphCommand.class,
        AiCommand.class,
        ListCommand.class,
        SystemProcessCommand.class
    }
)
public class Main implements Runnable {

    public static void main(String[] args) {
        // If no subcommand is specified, default to "status"
        if (args.length > 0 && !args[0].startsWith("-") && isNumericOrFile(args[0])) {
            // First arg is PID or file, prepend "status"
            String[] newArgs = new String[args.length + 1];
            newArgs[0] = "status";
            System.arraycopy(args, 0, newArgs, 1, args.length);
            args = newArgs;
        }
        int exitCode = MiniCli.builder()
            .commandConfig(Main::setMiniCliCommandConfig)
            .run(new Main(), args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static void setMiniCliCommandConfig(CommandConfig cfg) {
        cfg.version = "0.4.9";
        cfg.mixinStandardHelpOptions = true;
        cfg.defaultValueHelpTemplate = ", default is ${DEFAULT-VALUE}";
        cfg.defaultValueOnNewLine = false;
    }

    @Override
    public void run() {
        // Show available JVMs when no arguments provided
        System.out.println("Usage: jstall <command> <pid|file> [options]");
        System.out.println();
        System.out.println("Available commands:");
        System.out.println("  list              - List running JVM processes (optionally filter by name)");
        System.out.println("  status            - Show overall status (deadlocks + most active threads)");
        System.out.println("  deadlock          - Check for deadlocks");
        System.out.println("  most-work         - Show threads doing the most work");
        System.out.println("  flame             - Generate flame graph");
        System.out.println("  threads           - List all threads");
        System.out.println("  waiting-threads   - Identify threads waiting without progress");
        System.out.println("  dependency-graph  - Show thread dependencies (lock wait relationships)");
        System.out.println("  ai                - AI-powered analysis using LLM");
        System.out.println("  ai full           - AI-powered analysis of all JVMs on the system");
        System.out.println();
        JVMDiscovery.printAvailableJVMs(System.out);
    }

    private static boolean isNumericOrFile(String arg) {
        // Check if it's a PID (numeric) or a file path
        return arg.matches("\\d+") || arg.endsWith(".txt") || arg.contains("/") || arg.contains("\\");
    }
}