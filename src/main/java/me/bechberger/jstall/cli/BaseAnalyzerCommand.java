package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.model.ThreadDumpWithRaw;
import me.bechberger.jstall.provider.JThreadDumpProvider;
import me.bechberger.jstall.provider.ThreadDumpProvider;
import me.bechberger.jstall.util.JVMDiscovery;
import me.bechberger.jstall.util.TargetResolver;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static me.bechberger.jstall.cli.CommandHelper.parseDuration;

/**
 * Base class for analyzer-based commands.
 * Handles common logic for collecting dumps and running analyzers.
 */
public abstract class BaseAnalyzerCommand implements Callable<Integer> {

    @Parameters(
        index = "0..*",
        description = "PID, filter or dump files"
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
     * Returns whether this analyzer supports multiple targets.
     * Override to return false for analyzers like flame that only work with one target.
     */
    protected boolean supportsMultipleTargets() {
        return true;
    }

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

        // Resolve all targets
        TargetResolver.ResolutionResult resolution = TargetResolver.resolveMultiple(targets);

        if (!resolution.isSuccess()) {
            System.err.println("Error: " + resolution.errorMessage());
            if (resolution.shouldListJVMs()) {
                System.err.println();
                JVMDiscovery.printAvailableJVMs(System.err);
            }
            return 1;
        }

        List<TargetResolver.ResolvedTarget> resolvedTargets = resolution.targets();

        // Check if multiple targets are supported
        if (resolvedTargets.size() > 1 && !supportsMultipleTargets()) {
            System.err.println("Error: " + analyzer.name() + " does not support multiple targets");
            System.err.println("Found " + resolvedTargets.size() + " targets:");
            for (TargetResolver.ResolvedTarget target : resolvedTargets) {
                if (target instanceof TargetResolver.ResolvedTarget.Pid pid) {
                    System.err.println("  PID " + pid.pid() + ": " + pid.mainClass());
                } else if (target instanceof TargetResolver.ResolvedTarget.File file) {
                    System.err.println("  File: " + file.path());
                }
            }
            return 1;
        }

        // Process single or multiple targets
        if (resolvedTargets.size() == 1) {
            return processSingleTarget(resolvedTargets.get(0), analyzer);
        } else {
            return processMultipleTargets(resolvedTargets, analyzer);
        }
    }

    private Integer processSingleTarget(TargetResolver.ResolvedTarget target, Analyzer analyzer) throws Exception {
        ThreadDumpProvider provider = new JThreadDumpProvider();
        int dumpCount = dumps != null ? dumps : analyzer.defaultDumpCount();
        long intervalMs = interval != null ? parseDuration(interval) : analyzer.defaultIntervalMs();

        List<ThreadDumpWithRaw> threadDumps;

        if (target instanceof TargetResolver.ResolvedTarget.Pid pid) {
            // Collect from running JVM
            Path persistPath = keep ? Path.of("dumps") : null;
            threadDumps = provider.collectFromJVM(pid.pid(), dumpCount, intervalMs, persistPath);
        } else if (target instanceof TargetResolver.ResolvedTarget.File file) {
            // Load from file
            threadDumps = provider.loadFromFiles(List.of(file.path()));

            // Validate dump count for MANY requirement
            if (analyzer.dumpRequirement() == DumpRequirement.MANY && threadDumps.size() < 2) {
                System.err.println("Error: " + analyzer.name() + " requires at least 2 dumps, got " + threadDumps.size());
                return 1;
            }
        } else {
            throw new IllegalStateException("Unknown target type: " + target);
        }

        // Build options map
        Map<String, Object> options = buildOptions(dumpCount, intervalMs);

        // Run analyzer
        AnalyzerResult result = analyzer.analyze(threadDumps, options);
        System.out.println(result.output());
        return result.exitCode();
    }

    private Integer processMultipleTargets(List<TargetResolver.ResolvedTarget> targets, Analyzer analyzer) throws Exception {
        int dumpCount = dumps != null ? dumps : analyzer.defaultDumpCount();
        long intervalMs = interval != null ? parseDuration(interval) : analyzer.defaultIntervalMs();
        Map<String, Object> options = buildOptions(dumpCount, intervalMs);

        // Result holder for each target
        record TargetResult(TargetResolver.ResolvedTarget target, AnalyzerResult result, Exception error) {}

        // Run all analyses in parallel
        List<CompletableFuture<TargetResult>> futures = new ArrayList<>();

        for (TargetResolver.ResolvedTarget target : targets) {
            CompletableFuture<TargetResult> future = CompletableFuture.supplyAsync(() -> {
                try {
                    ThreadDumpProvider provider = new JThreadDumpProvider();
                    List<ThreadDumpWithRaw> threadDumps;

                    if (target instanceof TargetResolver.ResolvedTarget.Pid pid) {
                        Path persistPath = keep ? Path.of("dumps") : null;
                        threadDumps = provider.collectFromJVM(pid.pid(), dumpCount, intervalMs, persistPath);
                    } else if (target instanceof TargetResolver.ResolvedTarget.File file) {
                        threadDumps = provider.loadFromFiles(List.of(file.path()));
                    } else {
                        throw new IllegalStateException("Unknown target type: " + target);
                    }

                    AnalyzerResult result = analyzer.analyze(threadDumps, options);
                    return new TargetResult(target, result, null);

                } catch (Exception e) {
                    return new TargetResult(target, null, e);
                }
            });
            futures.add(future);
        }

        // Wait for all to complete
        CompletableFuture<Void> allOf = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        try {
            allOf.get();
        } catch (InterruptedException | ExecutionException e) {
            System.err.println("Error waiting for analyses to complete: " + e.getMessage());
            return 1;
        }

        // Collect all results
        List<TargetResult> results = new ArrayList<>();
        for (CompletableFuture<TargetResult> future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException | ExecutionException e) {
                System.err.println("Error retrieving analysis result: " + e.getMessage());
            }
        }

        // Sort results by PID (PIDs first, then files)
        results.sort((r1, r2) -> {
            boolean r1IsPid = r1.target instanceof TargetResolver.ResolvedTarget.Pid;
            boolean r2IsPid = r2.target instanceof TargetResolver.ResolvedTarget.Pid;

            if (r1IsPid && r2IsPid) {
                long pid1 = ((TargetResolver.ResolvedTarget.Pid) r1.target).pid();
                long pid2 = ((TargetResolver.ResolvedTarget.Pid) r2.target).pid();
                return Long.compare(pid1, pid2);
            } else if (r1IsPid) {
                return -1; // PIDs come before files
            } else if (r2IsPid) {
                return 1;
            } else {
                // Both are files, sort by path
                String path1 = ((TargetResolver.ResolvedTarget.File) r1.target).path().toString();
                String path2 = ((TargetResolver.ResolvedTarget.File) r2.target).path().toString();
                return path1.compareTo(path2);
            }
        });

        // Print results in sorted order
        int maxExitCode = 0;
        boolean first = true;

        for (TargetResult targetResult : results) {
            if (!first) {
                System.out.println("\n" + "=".repeat(80) + "\n");
            }
            first = false;

            // Print header
            if (targetResult.target instanceof TargetResolver.ResolvedTarget.Pid pid) {
                System.out.println("Analysis for PID " + pid.pid() + " (" + pid.mainClass() + "):");
            } else if (targetResult.target instanceof TargetResolver.ResolvedTarget.File file) {
                System.out.println("Analysis for file: " + file.path());
            }
            System.out.println();

            // Print result or error
            if (targetResult.error != null) {
                System.err.println("Error analyzing target: " + targetResult.error.getMessage());
                maxExitCode = Math.max(maxExitCode, 1);
            } else {
                System.out.println(targetResult.result.output());
                maxExitCode = Math.max(maxExitCode, targetResult.result.exitCode());
            }
        }

        return maxExitCode;
    }

    private Map<String, Object> buildOptions(int dumpCount, long intervalMs) {
        Map<String, Object> options = new HashMap<>();
        options.put("dumps", dumpCount);
        options.put("interval", interval != null ? interval : (intervalMs + "ms"));
        options.put("keep", keep);
        options.putAll(getAdditionalOptions());
        return options;
    }
}