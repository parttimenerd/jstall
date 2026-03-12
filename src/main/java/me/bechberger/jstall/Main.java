package me.bechberger.jstall;

import me.bechberger.jstall.cli.*;
import me.bechberger.jstall.cli.record.RecordMainCommand;
import me.bechberger.femtocli.CommandConfig;
import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.jstall.provider.ReplayProvider;
import me.bechberger.jstall.util.JVMDiscovery;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Main entry point for JStall.
 */
@Command(
    name = "jstall",
    description = "One-shot JVM inspection tool",
    version = "0.5.2",
    subcommands = {
        RecordMainCommand.class,
        StatusCommand.class,
        DeadLockCommand.class,
        MostWorkCommand.class,
        FlameCommand.class,
        ThreadsCommand.class,
        WaitingThreadsCommand.class,
        DependencyGraphCommand.class,
        VmVitalsCommand.class,
        GcHeapInfoCommand.class,
        VmClassloaderStatsCommand.class,
        VmMetaspaceCommand.class,
        CompilerQueueCommand.class,
        AiCommand.class,
        ListCommand.class,
        SystemProcessCommand.class,
        JvmSupportCommand.class
    },
    defaultSubcommand = StatusCommand.class
)
public class Main implements Runnable {

    public static final String VERSION = "0.4.11";

    @Option(names = {"-f", "--file"}, description = "File path for replay mode (replay ZIP file created by record command)")
    private Path replayFile;

    public static void main(String[] args) {
        int exitCode = FemtoCli.builder()
            .commandConfig(Main::setFemtoCliCommandConfig)
            .run(new Main(), args);
        if (exitCode != 0) {
            System.exit(exitCode);
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
        System.out.println("  vm-vitals         - Show VM.vitals (if available)");
        System.out.println("  gc-heap-info      - Show GC.heap_info last absolute values and deltas");
        System.out.println("  jvm-support       - Check whether the target JVM is likely still supported");
        System.out.println("  ai                - AI-powered analysis using LLM");
        System.out.println("  ai full           - AI-powered analysis of all JVMs on the system");
        System.out.println();

        if (replayFile != null) {
            try {
                var provider = new ReplayProvider(replayFile);
                provider.printReplayTargets(System.out);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            JVMDiscovery.printAvailableJVMs(System.out);
        }
    }

    public Path getReplayFile() {
        return replayFile;
    }
}