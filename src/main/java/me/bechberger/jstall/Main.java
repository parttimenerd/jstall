package me.bechberger.jstall;

import me.bechberger.jstall.cli.*;
import me.bechberger.jstall.cli.record.RecordMainCommand;
import me.bechberger.femtocli.CommandConfig;
import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.jstall.provider.ReplayProvider;
import me.bechberger.jstall.util.CommandExecutor;
import me.bechberger.jstall.util.CommandExecutor.SSHCommandException;
import me.bechberger.jstall.util.JVMDiscovery;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Main entry point for JStall.
 */
@Command(
    name = "jstall",
    description = "One-shot JVM inspection tool",
    version = "0.6.0",
    subcommands = {
        RecordMainCommand.class,
        StatusCommand.class,
        DeadLockCommand.class,
        MostWorkCommand.class,
        FlameCommand.class,
        ThreadsCommand.class,
        WaitingThreadsCommand.class,
        DependencyGraphCommand.class,
        DependencyTreeCommand.class,
        VmVitalsCommand.class,
        GcHeapInfoCommand.class,
        VmClassloaderStatsCommand.class,
        VmMetaspaceCommand.class,
        CompilerQueueCommand.class,
        AiCommand.class,
        ListCommand.class,
        SystemProcessCommand.class,
        JvmSupportCommand.class,
        HelpCommand.class
    },
    defaultSubcommand = StatusCommand.class
)
public class Main implements Runnable {

    public static final String VERSION = "0.6.0";

    @Option(names = {"-f", "--file"}, description = "File path for replay mode (replay ZIP file created by record command)")
    private Path replayFile;

    @Option(names = {"-s", "--ssh"}, description = "Execution command prefix for running commands on a remote host via SSH (e.g., 'ssh user@host'), only Linux/Mac support on remote", prevents = {"--cf", "--file"})
    private String sshCommandPrefix;

    @Option(names = "--cf", description = "Use Cloud Foundry CLI for remote execution (shortcut for --ssh 'cf ssh <app-name> -c'), only Linux/Mac support on remote", prevents = {"--ssh", "--file"})
    private String cfAppName;

    @Option(names = {"-v", "--verbose"}, description = "Enable verbose logging of remote SSH commands and their outputs")
    private boolean verbose;

    private volatile CommandExecutor cachedExecutor;

    public @NotNull synchronized CommandExecutor executor() {
        if (cachedExecutor == null) {
            if (sshCommandPrefix != null) {
                var remote = new CommandExecutor.RemoteCommandExecutor(sshCommandPrefix);
                remote.setVerbose(verbose);
                cachedExecutor = remote;
            } else if (cfAppName != null) {
                var remote = new CommandExecutor.RemoteCommandExecutor("cf ssh " + cfAppName + " -c");
                remote.setVerbose(verbose);
                cachedExecutor = remote;
            } else {
                cachedExecutor = new CommandExecutor.LocalCommandExecutor();
            }
        }
        return cachedExecutor;
    }

    public static void main(String[] args) {
        try {
            int exitCode = FemtoCli.builder()
                .commandConfig(Main::setFemtoCliCommandConfig)
                .run(new Main(), args);
            if (exitCode != 0) {
                System.exit(exitCode);
            }
        } catch (Exception e) {
            Throwable cause = e;
            while (cause != null) {
                if (cause instanceof SSHCommandException ssh) {
                    System.err.println("ERROR: " + ssh.getMessage());
                    System.exit(2);
                }
                cause = cause.getCause();
            }
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    public static void setFemtoCliCommandConfig(CommandConfig cfg) {
        cfg.version = VERSION;
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
        System.out.println("  record            - Record diagnostics and manage recording archives");
        System.out.println("  list              - List running JVM processes (optionally filter by name)");
        System.out.println("  status            - Show overall status (deadlocks + most active threads)");
        System.out.println("  deadlock          - Check for deadlocks");
        System.out.println("  most-work         - Show threads doing the most work");
        System.out.println("  flame             - Generate flame graph");
        System.out.println("  threads           - List all threads");
        System.out.println("  waiting-threads   - Identify threads waiting without progress");
        System.out.println("  dependency-graph  - Show thread dependencies (lock wait relationships)");
        System.out.println("  dependency-tree   - Show non deadlock thread dependencies over time");
        System.out.println("  vm-vitals         - Show VM.vitals (if available)");
        System.out.println("  gc-heap-info      - Show GC.heap_info last absolute values and deltas");
        System.out.println("  compiler-queue    - Show compiler queue");
        System.out.println("  vm-classloader-stats - Show VM classloader statistics");
        System.out.println("  vm-metaspace      - Show VM metaspace info");
        System.out.println("  jvm-support       - Check whether the target JVM is likely still supported");
        System.out.println("  processes         - Show system processes");
        System.out.println();

        if (replayFile != null) {
            try {
                var provider = new ReplayProvider(replayFile);
                provider.printReplayTargets(System.out);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            new JVMDiscovery(executor()).printAvailableJVMs(System.out);
        }
    }

    public Path getReplayFile() {
        return replayFile;
    }
}