package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.model.ThreadDumpWithRaw;
import me.bechberger.jstall.provider.JThreadDumpProvider;
import me.bechberger.jstall.provider.ThreadDumpProvider;
import me.bechberger.jstall.util.JVMDiscovery;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static me.bechberger.jstall.cli.CommandHelper.parseDuration;

/**
 * Base class for analyzer-based commands.
 * Handles common logic for collecting dumps and running analyzers.
 */
public abstract class BaseAnalyzerCommand implements Callable<Integer> {

    @Parameters(
        index = "0..*",
        description = "PID or dump files"
    )
    protected List<String> targets;

    @Option(names = "--dumps", description = "Number of dumps to collect, default is 2")
    protected Integer dumps;

    @Option(names = "--interval", description = "Interval between dumps, default is 5s")
    protected String interval;

    @Option(names = "--keep", description = "Persist dumps to disk")
    protected boolean keep = false;

    /**
     * Returns the analyzer to use for this command.
     */
    protected abstract Analyzer getAnalyzer();

    /**
     * Returns additional analyzer-specific options.
     * Override this to add custom options like --top.
     */
    protected Map<String, Object> getAdditionalOptions() {
        return new HashMap<>();
    }

    @Override
    public Integer call() throws Exception {
        Analyzer analyzer = getAnalyzer();

        // Show help and list JVMs if no targets specified
        if (targets == null || targets.isEmpty()) {
            new CommandLine(this).usage(System.out);
            System.out.println();
            JVMDiscovery.printAvailableJVMs(System.out);
            return 1;
        }

        ThreadDumpProvider provider = new JThreadDumpProvider();
        List<ThreadDumpWithRaw> threadDumps;

        // Determine dump count and interval from analyzer config
        int dumpCount = dumps != null ? dumps : analyzer.defaultDumpCount();
        long intervalMs = interval != null ? parseDuration(interval) : analyzer.defaultIntervalMs();

        // Determine if we're dealing with PID or files
        String firstTarget = targets.get(0);
        if (firstTarget.matches("\\d+")) {
            // PID
            long pid = Long.parseLong(firstTarget);
            Path persistPath = keep ? Path.of("dumps") : null;

            threadDumps = provider.collectFromJVM(pid, dumpCount, intervalMs, persistPath);
        } else {
            // Files
            List<Path> files = targets.stream().map(Path::of).toList();
            threadDumps = provider.loadFromFiles(files);

            // Validate dump count for MANY requirement
            if (analyzer.dumpRequirement() == DumpRequirement.MANY && threadDumps.size() < 2) {
                System.err.println("Error: " + analyzer.name() + " requires at least 2 dumps, got " + threadDumps.size());
                return 1;
            }
        }

        // Build options map
        Map<String, Object> options = new HashMap<>();
        options.put("dumps", dumpCount);
        options.put("interval", interval != null ? interval : (intervalMs + "ms"));
        options.put("keep", keep);

        // Add additional options
        options.putAll(getAdditionalOptions());

        // Run analyzer
        AnalyzerResult result = analyzer.analyze(threadDumps, options);

        System.out.println(result.output());
        return result.exitCode();
    }
}