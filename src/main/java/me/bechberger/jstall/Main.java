package me.bechberger.jstall;

import me.bechberger.jstall.cli.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Main entry point for JStall.
 */
@Command(
    name = "jstall",
    description = "One-shot JVM inspection tool",
    mixinStandardHelpOptions = true,
    version = "0.2.0",
    subcommands = {
        StatusCommand.class,
        DeadLockCommand.class,
        MostWorkCommand.class,
        FlameCommand.class,
        ThreadsCommand.class
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

        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        // Default to status if no subcommand
        new CommandLine(new StatusCommand()).execute();
    }

    private static boolean isNumericOrFile(String arg) {
        // Check if it's a PID (numeric) or a file path
        return arg.matches("\\d+") || arg.endsWith(".txt") || arg.contains("/") || arg.contains("\\");
    }
}