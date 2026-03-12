package me.bechberger.jstall.cli;

import me.bechberger.jstall.Main;
import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.femtocli.Spec;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
import me.bechberger.jstall.provider.JThreadDumpProvider;
import me.bechberger.jstall.provider.ReplayProvider;
import me.bechberger.jstall.provider.ThreadDumpProvider;
import me.bechberger.jstall.provider.DataCollector;
import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jstall.provider.requirement.DataRequirement;
import me.bechberger.jstall.util.JVMDiscovery;
import me.bechberger.jstall.util.JMXDiagnosticHelper;
import me.bechberger.jstall.util.TargetResolver;
import me.bechberger.femtocli.annotations.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Base class for analyzer-based commands.
 * Handles common logic for collecting dumps and running analyzers.
 */
public abstract class BaseAnalyzerCommand implements Callable<Integer> {

    @Parameters(
        index = "0..*",
        description = "PID, 'all', filter or dump files (or replay ZIP as first argument)"
    )
    protected List<String> targets;

    @Option(names = "--dumps", description = "Number of dumps to collect, default is ${DEFAULT-VALUE}")
    protected Integer dumps;

    @Option(names = "--interval", defaultValue = "5s", description = "Interval between dumps, default is ${DEFAULT-VALUE}")
    protected Duration interval;

    @Option(names = "--keep", description = "Persist dumps to disk")
    protected boolean keep = false;

    @Option(names = "--intelligent-filter", description = "Use intelligent stack trace filtering (collapses internal frames, focuses on application code)")
    protected Boolean intelligentFilter;

    @Option(names = "--full", description = "Run all analyses including expensive ones (only for status command)")
    protected boolean full = false;

    Spec spec;
    private Path positionalReplayFile;

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

    /**
     * Safely retrieves the replay file path from the parent Main command.
     * Returns null if spec is not injected (e.g. direct instantiation in tests)
     * or if no replay file was specified.
     */
    private Path getReplayFilePath() {
        if (spec == null) return null;
        Main main = spec.getParent(Main.class);
        return main != null ? main.getReplayFile() : null;
    }

    private Path getEffectiveReplayFilePath() {
        return positionalReplayFile != null ? positionalReplayFile : getReplayFilePath();
    }

    @Override
    public Integer call() throws Exception {
        positionalReplayFile = null;
        List<String> effectiveTargets = targets == null ? new ArrayList<>() : new ArrayList<>(targets);
        if (getReplayFilePath() == null) {
            positionalReplayFile = extractPositionalReplayFile(effectiveTargets);
        }

        Analyzer analyzer = getAnalyzer();
        boolean replayMode = getEffectiveReplayFilePath() != null;

        TargetResolver.ResolutionResult resolution;

        // Show help and list JVMs if no targets specified
        if (effectiveTargets.isEmpty()) {
            if (positionalReplayFile != null) {
                resolution = resolveTargetsFromReplay(List.of());
            } else {
                spec.usage();
                System.out.println();
                if (replayMode) {
                    ReplayProvider provider = new ReplayProvider(getEffectiveReplayFilePath());
                    provider.printReplayTargets(System.out);
                } else {
                    JVMDiscovery.printAvailableJVMs(System.out);
                }
                return 1;
            }
        } else {
            // Resolve all targets
            resolution = replayMode
                ? resolveTargetsFromReplay(effectiveTargets)
                : TargetResolver.resolveMultiple(effectiveTargets);
        }

        if (!resolution.isSuccess()) {
            System.err.println("Error: " + resolution.errorMessage());
            if (resolution.shouldListJVMs()) {
                System.err.println();
                JVMDiscovery.printAvailableJVMs(System.err);
            }
            return 1;
        }

        List<TargetResolver.ResolvedTarget> resolvedTargets = resolution.targets();

        // If the user passed multiple files, interpret them as multiple dumps for a single analysis.
        // This is important for analyzers with DumpRequirement.MANY (e.g. status/most-work),
        // where multiple dumps are expected.
        boolean allFiles = resolvedTargets.stream().allMatch(t -> t instanceof TargetResolver.ResolvedTarget.File);
        if (allFiles && resolvedTargets.size() > 1) {
            return processMultipleDumpFiles(resolvedTargets, analyzer);
        }

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

    private Integer processMultipleDumpFiles(List<TargetResolver.ResolvedTarget> targets, Analyzer analyzer) throws Exception {
        // All targets are files (validated by caller)
        List<Path> paths = targets.stream()
                .map(t -> (TargetResolver.ResolvedTarget.File) t)
                .map(TargetResolver.ResolvedTarget.File::path)
                .toList();

        ThreadDumpProvider provider = createProvider();
        List<ThreadDumpSnapshot> threadDumps = provider.loadFromFiles(paths);

        int dumpCount = dumps != null ? dumps : analyzer.defaultDumpCount();
        long intervalMs = interval != null ? interval.toMillis() : analyzer.defaultIntervalMs();

        if (analyzer.dumpRequirement() == DumpRequirement.MANY && threadDumps.size() < 2) {
            System.err.println("Error: " + analyzer.name() + " requires at least 2 dumps, got " + threadDumps.size());
            return 1;
        }

        Map<String, Object> options = buildOptions(dumpCount, intervalMs);
        ResolvedData data = buildResolvedData(provider, null, threadDumps, analyzer, options);
        AnalyzerResult result = analyzer.analyze(data, options);
        System.out.println(result.output());
        return result.exitCode();
    }

    private Integer processSingleTarget(TargetResolver.ResolvedTarget target, Analyzer analyzer) throws Exception {
        ThreadDumpProvider provider = createProvider();
        int dumpCount = dumps != null ? dumps : analyzer.defaultDumpCount();
        long intervalMs = interval != null ? interval.toMillis() : analyzer.defaultIntervalMs();

        List<ThreadDumpSnapshot> threadDumps;

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
        ResolvedData data = buildResolvedData(provider, target, threadDumps, analyzer, options);
        AnalyzerResult result = analyzer.analyze(data, options);
        System.out.println(result.output());
        return result.exitCode();
    }

    private Integer processMultipleTargets(List<TargetResolver.ResolvedTarget> targets, Analyzer analyzer) {
         int dumpCount = dumps != null ? dumps : analyzer.defaultDumpCount();
         long intervalMs = interval != null ? interval.toMillis() : analyzer.defaultIntervalMs();
         Map<String, Object> options = buildOptions(dumpCount, intervalMs);

        // Result holder for each target
        record TargetResult(TargetResolver.ResolvedTarget target, AnalyzerResult result, Exception error) {}

        // Run all analyses in parallel
        List<CompletableFuture<TargetResult>> futures = new ArrayList<>();

        for (TargetResolver.ResolvedTarget target : targets) {
            CompletableFuture<TargetResult> future = CompletableFuture.supplyAsync(() -> {
                try {
                    ThreadDumpProvider provider = createProvider();
                    List<ThreadDumpSnapshot> threadDumps;

                    if (target instanceof TargetResolver.ResolvedTarget.Pid pid) {
                        Path persistPath = keep ? Path.of("dumps") : null;
                        threadDumps = provider.collectFromJVM(pid.pid(), dumpCount, intervalMs, persistPath);
                    } else if (target instanceof TargetResolver.ResolvedTarget.File file) {
                        threadDumps = provider.loadFromFiles(List.of(file.path()));
                    } else {
                        throw new IllegalStateException("Unknown target type: " + target);
                    }

                    ResolvedData data = buildResolvedData(provider, target, threadDumps, analyzer, options);
                    AnalyzerResult result = analyzer.analyze(data, options);
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
                System.out.println();
            }
            first = false;

            // Print header
            if (targetResult.target instanceof TargetResolver.ResolvedTarget.Pid pid) {
                System.out.println("====== PID " + pid.pid() + " (" + pid.mainClass() + ") ======");
            } else if (targetResult.target instanceof TargetResolver.ResolvedTarget.File file) {
                System.out.println("====== FILE " + file.path() + " ======");
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
        options.put("interval", intervalMs);
        options.put("keep", keep);
        if (intelligentFilter != null) {
            options.put("intelligent-filter", intelligentFilter);
        }
        options.putAll(getAdditionalOptions());
        return options;
    }

    private ThreadDumpProvider createProvider() throws IOException {
        Path replayFile = getEffectiveReplayFilePath();
        if (replayFile != null) {
            return new ReplayProvider(replayFile);
        }
        return new JThreadDumpProvider();
    }

    private ResolvedData buildResolvedData(ThreadDumpProvider provider,
                                           TargetResolver.ResolvedTarget target,
                                           List<ThreadDumpSnapshot> threadDumps,
                                           Analyzer analyzer,
                                           Map<String, Object> options) {
        if (provider instanceof ReplayProvider replayProvider && target instanceof TargetResolver.ResolvedTarget.Pid pid) {
            try {
                Map<String, List<CollectedData>> collectedDataByType = replayProvider.loadCollectedDataByTypeForPid(pid.pid());
                return ResolvedData.fromDumpsAndCollectedData(threadDumps, collectedDataByType);
            } catch (IOException ignored) {
                // Fall back to thread-dump-only resolved data if replay extras cannot be loaded.
            }
        }

        if (!(provider instanceof ReplayProvider) && target instanceof TargetResolver.ResolvedTarget.Pid pid) {
            try {
                Map<String, List<CollectedData>> collectedDataByType = collectLiveDataByType(pid.pid(), analyzer, options);
                return ResolvedData.fromDumpsAndCollectedData(threadDumps, collectedDataByType);
            } catch (Exception e) {
                // Fall back to thread-dump-only resolved data if live requirement collection fails.
                System.err.println("[jstall] Warning: live jcmd data collection failed, jcmd-based analyses will be skipped: " + e.getMessage());
            }
        }

        return ResolvedData.fromDumps(threadDumps);
    }

    private Map<String, List<CollectedData>> collectLiveDataByType(long pid,
                                                                    Analyzer analyzer,
                                                                    Map<String, Object> options) throws IOException {
        Map<String, List<CollectedData>> byType = new HashMap<>();
        var requirements = analyzer.getDataRequirements(options);

        try (JMXDiagnosticHelper helper = new JMXDiagnosticHelper(pid)) {
            DataCollector collector = new DataCollector(helper, requirements);
            Map<DataRequirement, List<CollectedData>> collected = collector.collectAll();

            for (Map.Entry<DataRequirement, List<CollectedData>> entry : collected.entrySet()) {
                String type = entry.getKey().getType();
                if (type == null || type.isBlank()) {
                    continue;
                }
                byType.computeIfAbsent(type, __ -> new ArrayList<>()).addAll(entry.getValue());
            }
        }

        byType.replaceAll((__, samples) -> samples.stream()
            .sorted(java.util.Comparator.comparingLong(CollectedData::timestamp))
            .toList());

        return byType;
    }

    private TargetResolver.ResolutionResult resolveTargetsFromReplay(List<String> requestedTargets) {
        try {
            ReplayProvider provider = new ReplayProvider(getEffectiveReplayFilePath());
            List<JVMDiscovery.JVMProcess> recorded = provider.listRecordedJvms(null);

            if (requestedTargets == null || requestedTargets.isEmpty()) {
                if (recorded.isEmpty()) {
                    return TargetResolver.ResolutionResult.error("No recorded JVMs found in replay file", false);
                }
                List<TargetResolver.ResolvedTarget> allRecorded = recorded.stream()
                    .map(jvm -> new TargetResolver.ResolvedTarget.Pid(jvm.pid(), jvm.mainClass()))
                    .map(t -> (TargetResolver.ResolvedTarget) t)
                    .toList();
                return TargetResolver.ResolutionResult.success(allRecorded);
            }

            List<TargetResolver.ResolvedTarget> resolved = new ArrayList<>();

            for (String target : requestedTargets) {
                if (target == null || target.isBlank()) {
                    continue;
                }

                if (target.equalsIgnoreCase("all")) {
                    if (recorded.isEmpty()) {
                        return TargetResolver.ResolutionResult.error("No recorded JVMs found in replay file", false);
                    }
                    resolved.addAll(recorded.stream()
                        .map(jvm -> new TargetResolver.ResolvedTarget.Pid(jvm.pid(), jvm.mainClass()))
                        .map(t -> (TargetResolver.ResolvedTarget) t)
                        .toList());
                    continue;
                }

                Path filePath = Path.of(target);
                if (java.nio.file.Files.exists(filePath) && java.nio.file.Files.isRegularFile(filePath)) {
                    resolved.add(new TargetResolver.ResolvedTarget.File(filePath));
                    continue;
                }

                if (target.matches("\\d+")) {
                    long pid = Long.parseLong(target);
                    JVMDiscovery.JVMProcess match = recorded.stream()
                        .filter(jvm -> jvm.pid() == pid)
                        .findFirst()
                        .orElse(null);
                    if (match == null) {
                        return TargetResolver.ResolutionResult.error("No recorded JVM found with PID " + pid, false);
                    }
                    resolved.add(new TargetResolver.ResolvedTarget.Pid(match.pid(), match.mainClass()));
                    continue;
                }

                String filter = target.toLowerCase();
                List<JVMDiscovery.JVMProcess> matches = recorded.stream()
                    .filter(jvm -> jvm.mainClass().toLowerCase().contains(filter))
                    .toList();
                if (matches.isEmpty()) {
                    return TargetResolver.ResolutionResult.error("No recorded JVMs found matching filter: " + target, false);
                }
                for (JVMDiscovery.JVMProcess match : matches) {
                    resolved.add(new TargetResolver.ResolvedTarget.Pid(match.pid(), match.mainClass()));
                }
            }

            if (resolved.isEmpty()) {
                return TargetResolver.ResolutionResult.error("No targets specified", false);
            }
            return TargetResolver.ResolutionResult.success(resolved);
        } catch (IOException e) {
            return TargetResolver.ResolutionResult.error("Failed to open replay file: " + e.getMessage(), false);
        }
    }

    private Path extractPositionalReplayFile(List<String> effectiveTargets) {
        if (effectiveTargets == null || effectiveTargets.isEmpty()) {
            return null;
        }

        String first = effectiveTargets.get(0);
        if (first == null || first.isBlank()) {
            return null;
        }

        Path candidate = Path.of(first);
        if (!Files.exists(candidate) || !Files.isRegularFile(candidate)) {
            return null;
        }
        if (!candidate.getFileName().toString().toLowerCase().endsWith(".zip")) {
            return null;
        }

        try {
            new ReplayProvider(candidate);
            effectiveTargets.remove(0);
            return candidate;
        } catch (IOException ignored) {
            return null;
        }
    }
}