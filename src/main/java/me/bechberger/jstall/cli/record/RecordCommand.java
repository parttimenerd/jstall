package me.bechberger.jstall.cli.record;

import me.bechberger.femtocli.Spec;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.femtocli.annotations.Parameters;
import me.bechberger.jstall.Main;
import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.impl.*;
import me.bechberger.jstall.provider.RecordingProvider;
import me.bechberger.jstall.provider.requirement.AsyncProfilerWindowRequirement;
import me.bechberger.jstall.provider.requirement.DataRequirements;
import me.bechberger.jstall.util.JVMDiscovery;
import me.bechberger.jstall.util.JcmdCommands;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Records diagnostic data from one or more JVMs into a replayable ZIP file.
 */
@Command(
    name = "create",
    description = "Record all data into a zip for later analysis"
)
public class RecordCommand implements Callable<Integer> {

    Spec spec;

    @Parameters(
            arity = "0..1",
        description = "Target: all | PID | filter"
    )
    private String target = "all";

    @Option(names = {"-o", "--output"}, required = true, description = "Output ZIP file")
    private Path output;

    @Option(names = "--count", defaultValue = "2", description = "Number of samples for interval-based data")
    private int count;

    @Option(names = "--interval", defaultValue = "5s", description = "Interval between samples")
    private Duration interval;

    @Option(names = "--include", description = "Additional jcmd command to record (repeatable)")
    private List<String> include;

    @Option(names = "--full", description = "Include expensive diagnostics (VM.classes, VM.class_hierarchy, GC.class_histogram, flame graph and JFR recording)")
    private boolean full;

    @Option(names = "--list-jcmd", description = "List common jcmd commands and exit")
    private boolean listJcmd;

    @Option(names = "--no-parallel", description = "Disable parallel recording across JVMs")
    private boolean noParallel;

    @Option(names = {"-v", "--verbose"}, description = "Enable verbose logging")
    private boolean verbose;

    @Override
    public Integer call() throws Exception {
        if (listJcmd) {
            System.out.println(JcmdCommands.formatCommandList());
            return 0;
        }

        if (count < 1) {
            System.err.println("Error: --count must be >= 1");
            return 1;
        }

        long intervalMs = interval.toMillis();
        if (intervalMs < 1) {
            System.err.println("Error: --interval must be >= 1ms");
            return 1;
        }

        List<JVMDiscovery.JVMProcess> targets = resolveTargets(target);
        if (targets.isEmpty()) {
            System.err.println("No JVM targets found for: " + target);
            return 1;
        }

        if (verbose) {
            System.out.println("Recording " + targets.size() + " JVM(s):");
            for (JVMDiscovery.JVMProcess proc : targets) {
                System.out.println("  - PID " + proc.pid() + ": " + proc.mainClass());
            }
        }

        DataRequirements requirements = collectRequirements(count, intervalMs, full);

        // Also collect metadata-only info: VM.flags, VM.command_line, and VM.uptime
        // (these will go to metadata.json instead of separate files)
        DataRequirements metadataRequirements = DataRequirements.builder()
            .addJcmdOnce("VM.flags")
            .addJcmdOnce("VM.command_line")
            .addJcmdOnce("VM.uptime")
            .build();
        requirements = requirements.merge(metadataRequirements);

        if (verbose) {
            System.out.println("Data requirements: " + requirements.getRequirements().size() + " requirement(s)");
        }

        var executor = spec.getParent(Main.class).executor();

        RecordingProvider provider = new RecordingProvider(executor, Main.VERSION, verbose);
        RecordingProvider.RecordingSummary summary = provider.record(targets, requirements, output, !noParallel);

        System.out.println("Recorded " + summary.successCount() + "/" + summary.targetCount() +
            " JVM(s) to " + summary.outputFile());
        if (summary.failureCount() > 0) {
            System.err.println("Warning: " + summary.failureCount() + " JVM(s) failed to record. Check metadata.json for details.");
            return 2;
        }

        return 0;
    }

    private DataRequirements collectRequirements(int count, long intervalMs, boolean full) {
        Map<String, Object> options = Map.of(
            "dumps", count,
            "interval", intervalMs
        );

        // Start with fast VM info that's inexpensive to collect
        DataRequirements.Builder builder = DataRequirements.builder()
            .withDefaults(count, intervalMs)
            .addFastVmInfo()
            .addSystemProps();
        
        // Add expensive VM diagnostics if --full is specified
        if (full) {
            builder.addJcmdOnce("VM.classes");
            builder.addJcmdOnce("VM.class_hierarchy");
            builder.addJcmdOnce("GC.class_histogram");
        }
        
        DataRequirements merged = builder.build();

        // Merge in analyzer-specific requirements
        for (Analyzer analyzer : allRecordableAnalyzers()) {
            merged = merged.merge(analyzer.getDataRequirements(options));
        }

        if (count > 1 && full && AsyncProfilerWindowRequirement.isPlatformSupported()) {
            DataRequirements profiling = DataRequirements.builder()
                .withDefaults(count, intervalMs)
                .addAsyncProfilerWindows()
                .build();
            merged = merged.merge(profiling);
        }

        if (include != null) {
            DataRequirements.Builder extras = DataRequirements.builder().withDefaults(count, intervalMs);
            for (String command : include) {
                if (command == null || command.isBlank()) {
                    continue;
                }
                String trimmed = command.trim();
                String validation = JcmdCommands.validate(trimmed);
                if (validation != null) {
                    System.err.println("Warning: " + validation + " Recording anyway.");
                }
                extras.addJcmd(trimmed);
            }
            merged = merged.merge(extras.build());
        }

        return merged;
    }

    private List<JVMDiscovery.JVMProcess> resolveTargets(String value) throws IOException {
        var executor = spec.getParent(Main.class).executor();
        var discovery = new JVMDiscovery(executor);
        if (value == null || value.isBlank() || value.equalsIgnoreCase("all")) {
            return discovery.listJVMs();
        }

        if (value.matches("\\d+")) {
            long pid = Long.parseLong(value);
            for (JVMDiscovery.JVMProcess process : discovery.listJVMs()) {
                if (process.pid() == pid) {
                    return List.of(process);
                }
            }
            return List.of();
        }

        return discovery.listJVMs(value);
    }

    private List<Analyzer> allRecordableAnalyzers() {
        List<Analyzer> analyzers = new ArrayList<>();
        analyzers.add(new StatusAnalyzer());
        analyzers.add(new DeadLockAnalyzer());
        analyzers.add(new MostWorkAnalyzer());
        analyzers.add(new ThreadsAnalyzer());
        analyzers.add(new WaitingThreadsAnalyzer());
        analyzers.add(new DependencyGraphAnalyzer());
        analyzers.add(new DependencyTreeAnalyzer());
        analyzers.add(new SystemProcessAnalyzer());
        analyzers.add(new JvmSupportAnalyzer());
        return analyzers;
    }
}