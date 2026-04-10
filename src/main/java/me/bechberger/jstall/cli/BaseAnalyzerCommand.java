package me.bechberger.jstall.cli;

import me.bechberger.jstall.Main;
import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.femtocli.Spec;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.jstall.model.SystemEnvironment;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
import me.bechberger.jstall.provider.ReplayProvider;
import me.bechberger.jstall.provider.DataCollector;
import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jstall.provider.requirement.DataRequirement;
import me.bechberger.jstall.provider.requirement.DataRequirements;
import me.bechberger.jstall.provider.requirement.ThreadDumpRequirement;
import me.bechberger.jstall.util.CommandExecutor;
import me.bechberger.jstall.util.CommandExecutor.SSHCommandException;
import me.bechberger.jstall.util.JVMDiscovery;
import me.bechberger.femtocli.annotations.Parameters;
import me.bechberger.jstall.util.ResolvedTarget;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
        setupReplayFile();
        AnalysisContext context;
        try {
            context = createContext();
        } catch (SSHCommandException e) {
            System.err.println("ERROR: " + e.getMessage());
            return 2;
        }

        JVMDiscovery.ResolutionResult resolution;
        try {
            resolution = resolveTargets(context);
        } catch (SSHCommandException e) {
            System.err.println("ERROR: " + e.getMessage());
            return 2;
        }
        if (!resolution.isSuccess()) {
            printResolutionError(resolution, context);
            return 1;
        }

        return processTargets(resolution.targets(), context);
    }

    private void setupReplayFile() {
        positionalReplayFile = null;
        List<String> effectiveTargets = targets == null ? new ArrayList<>() : new ArrayList<>(targets);
        if (getReplayFilePath() == null) {
            positionalReplayFile = extractPositionalReplayFile(effectiveTargets);
        }
    }

    private record AnalysisContext(
        Analyzer analyzer,
        CommandExecutor executor,
        JVMDiscovery discovery,
        int dumpCount,
        long intervalMs,
        Map<String, Object> options,
        boolean replayMode
    ) {}

    private record TargetResult(ResolvedTarget target, AnalyzerResult result, Exception error) {}

    private AnalysisContext createContext() throws IOException {
        Analyzer analyzer = getAnalyzer();
        CommandExecutor executor = resolveExecutor();
        JVMDiscovery discovery = new JVMDiscovery(executor);
        boolean replayMode = getEffectiveReplayFilePath() != null;
        int dumpCount = computeDumpCount(analyzer);
        long intervalMs = computeIntervalMs(analyzer);
        Map<String, Object> options = buildOptions(dumpCount, intervalMs);
        return new AnalysisContext(analyzer, executor, discovery, dumpCount, intervalMs, options, replayMode);
    }

    private JVMDiscovery.ResolutionResult resolveTargets(AnalysisContext context) throws IOException {
        List<String> effectiveTargets = targets == null ? new ArrayList<>() : new ArrayList<>(targets);
        if (getReplayFilePath() == null && effectiveTargets.size() > 0) {
            extractPositionalReplayFile(effectiveTargets);
        }
        return resolveTargets(effectiveTargets, context.replayMode, context.discovery);
    }

    private Integer processTargets(List<ResolvedTarget> resolvedTargets, AnalysisContext context) throws Exception {
        if (allTargetsAreFiles(resolvedTargets)) {
            return processMultipleDumpFiles(resolvedTargets, context);
        }
        if (resolvedTargets.size() > 1 && !supportsMultipleTargets()) {
            printMultipleTargetsNotSupportedError(context.analyzer, resolvedTargets);
            return 1;
        }
        if (resolvedTargets.size() == 1) {
            return processSingleTarget(resolvedTargets.get(0), context);
        }
        return processMultipleTargets(resolvedTargets, context);
    }

    private CommandExecutor resolveExecutor() {
        return (spec != null)
                ? spec.getParent(Main.class).executor()
                : new CommandExecutor.LocalCommandExecutor();
    }

    private JVMDiscovery.ResolutionResult resolveTargets(List<String> effectiveTargets, boolean replayMode, JVMDiscovery discovery) throws IOException {
        if (effectiveTargets.isEmpty()) {
            if (positionalReplayFile != null) {
                return resolveTargetsFromReplay(List.of());
            } else {
                spec.usage();
                System.out.println();
                if (replayMode) {
                    new ReplayProvider(getEffectiveReplayFilePath()).printReplayTargets(System.out);
                } else {
                    discovery.printAvailableJVMs(System.out);
                }
                return JVMDiscovery.ResolutionResult.error("No targets specified", false);
            }
        } else {
            return replayMode
                    ? resolveTargetsFromReplay(effectiveTargets)
                    : discovery.resolveMultiple(effectiveTargets);
        }
    }

    private void printResolutionError(JVMDiscovery.ResolutionResult resolution, AnalysisContext context) throws IOException {
        System.err.println("Error: " + resolution.errorMessage());
        if (shouldPrintAvailableTargets(resolution)) {
            System.err.println();
            printAvailableTargets(System.err, context);
        }
    }

    private boolean shouldPrintAvailableTargets(JVMDiscovery.ResolutionResult resolution) {
        if (!resolution.shouldListJVMs()) {
            return false;
        }
        String message = resolution.errorMessage();
        if (message == null) {
            return true;
        }
        String normalized = message.trim().toLowerCase(Locale.ROOT);
        return !normalized.equals("no jvms found") && !normalized.equals("no running jvms found");
    }

    private void printAvailableTargets(java.io.PrintStream out, AnalysisContext context) throws IOException {
        if (context.replayMode) {
            new ReplayProvider(getEffectiveReplayFilePath()).printReplayTargets(out);
        } else {
            context.discovery.printAvailableJVMs(out);
        }
    }

    private boolean allTargetsAreFiles(List<ResolvedTarget> resolvedTargets) {
        return resolvedTargets.stream().allMatch(t -> t instanceof ResolvedTarget.File) && resolvedTargets.size() > 1;
    }

    private void printMultipleTargetsNotSupportedError(Analyzer analyzer, List<ResolvedTarget> resolvedTargets) {
        System.err.println("Error: " + analyzer.name() + " does not support multiple targets");
        System.err.println("Found " + resolvedTargets.size() + " targets:");
        for (ResolvedTarget target : resolvedTargets) {
            if (target instanceof ResolvedTarget.Pid pid) {
                System.err.println("  PID " + pid.pid() + ": " + pid.mainClass());
            } else if (target instanceof ResolvedTarget.File file) {
                System.err.println("  File: " + file.path());
            }
        }
    }

    private Integer processMultipleDumpFiles(List<ResolvedTarget> targets, AnalysisContext context) throws Exception {
        List<Path> paths = targets.stream()
                .map(t -> (ResolvedTarget.File) t)
                .map(ResolvedTarget.File::path)
                .toList();

        List<ThreadDumpSnapshot> threadDumps = ThreadDumpRequirement.loadFromFiles(paths);
        if (!validateDumpRequirement(threadDumps, context.analyzer)) {
            return 1;
        }

        ResolvedData data = ResolvedData.fromDumps(threadDumps);
        return analyzeAndPrintResult(data, context);
    }

    private Integer processSingleTarget(ResolvedTarget target, AnalysisContext context) throws Exception {
        LoadedTargetData targetData = loadTargetData(context.executor, target, context.analyzer, context.dumpCount, context.options);
        if (targetData.error() != null) {
            return 1;
        }

        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(targetData.threadDumps(), targetData.collectedDataByType());
        return analyzeAndPrintResult(data, context);
    }

    private Integer processMultipleTargets(List<ResolvedTarget> targets, AnalysisContext context) {
        // Run all analyses in parallel and collect results
        List<TargetResult> results = runAnalysesInParallel(targets, context);
        if (results == null) {
            return 1; // Error occurred during parallel execution
        }

        // Sort and print results
        results.sort((r1, r2) -> compareTargets(r1.target, r2.target));
        return printAndAggregateResults(results);
    }

    private List<TargetResult> runAnalysesInParallel(List<ResolvedTarget> targets, AnalysisContext context) {
        List<CompletableFuture<TargetResult>> futures = new ArrayList<>();
        for (ResolvedTarget target : targets) {
            futures.add(runAnalysisForTarget(target, context));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        } catch (InterruptedException | ExecutionException e) {
            System.err.println("Error waiting for analyses to complete: " + e.getMessage());
            return null;
        }

        List<TargetResult> results = new ArrayList<>();
        for (CompletableFuture<TargetResult> future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException | ExecutionException e) {
                System.err.println("Error retrieving analysis result: " + e.getMessage());
            }
        }
        return results;
    }

    private CompletableFuture<TargetResult> runAnalysisForTarget(ResolvedTarget target, AnalysisContext context) {
        return CompletableFuture.supplyAsync(() -> analyzeTarget(target, context));
    }

    private TargetResult analyzeTarget(ResolvedTarget target, AnalysisContext context) {
        try {
            LoadedTargetData targetData = loadTargetData(context.executor, target, context.analyzer, context.dumpCount, context.options);
            if (targetData.error() != null) {
                return new TargetResult(target, null, targetData.error());
            }

            ResolvedData data = ResolvedData.fromDumpsAndCollectedData(targetData.threadDumps(), targetData.collectedDataByType());
            AnalyzerResult result = context.analyzer.analyze(data, context.options);
            return new TargetResult(target, result, null);
        } catch (Exception e) {
            return new TargetResult(target, null, e);
        }
    }

    private int printAndAggregateResults(List<TargetResult> results) {
        int maxExitCode = 0;
        boolean first = true;

        for (TargetResult targetResult : results) {
            if (!first) {
                System.out.println();
            }
            first = false;

            printTargetHeader(targetResult.target);
            System.out.println();

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

    private int analyzeAndPrintResult(ResolvedData data, AnalysisContext context) {
        AnalyzerResult result = context.analyzer.analyze(data, context.options);
        System.out.println(result.output());
        return result.exitCode();
    }

    private int computeDumpCount(Analyzer analyzer) {
        return dumps != null ? dumps : analyzer.defaultDumpCount();
    }

    private long computeIntervalMs(Analyzer analyzer) {
        return interval != null ? interval.toMillis() : analyzer.defaultIntervalMs();
    }

    private boolean validateDumpRequirement(List<ThreadDumpSnapshot> threadDumps, Analyzer analyzer) {
        if (analyzer.dumpRequirement() == DumpRequirement.MANY && threadDumps.size() < 2) {
            System.err.println("Error: " + analyzer.name() + " requires at least 2 dumps, got " + threadDumps.size());
            return false;
        }
        return true;
    }

    private record LoadedTargetData(List<ThreadDumpSnapshot> threadDumps, Map<String, List<CollectedData>> collectedDataByType, Exception error) {
        LoadedTargetData(List<ThreadDumpSnapshot> threadDumps, Map<String, List<CollectedData>> collectedDataByType) {
            this(threadDumps, collectedDataByType, null);
        }
        LoadedTargetData(Exception error) {
            this(null, null, error);
        }
    }

    private LoadedTargetData loadTargetData(CommandExecutor executor, ResolvedTarget target, Analyzer analyzer, int dumpCount, Map<String, Object> options) throws Exception {
        if (target instanceof ResolvedTarget.Pid pid) {
            return loadDataForPid(executor, pid, analyzer, dumpCount, options);
        } else if (target instanceof ResolvedTarget.File file) {
            return loadDataForFile(file, analyzer);
        } else {
            throw new IllegalStateException("Unknown target type: " + target);
        }
    }

    private LoadedTargetData loadDataForPid(CommandExecutor executor, ResolvedTarget.Pid pid, Analyzer analyzer, int dumpCount, Map<String, Object> options) throws Exception {
        Path replayFile = getEffectiveReplayFilePath();
        if (replayFile != null) {
            return loadDataFromReplay(replayFile, pid, dumpCount);
        } else {
            return loadDataFromCollection(executor, pid, analyzer, options);
        }
    }

    private LoadedTargetData loadDataFromReplay(Path replayFile, ResolvedTarget.Pid pid, int dumpCount) throws IOException {
        ReplayProvider replay = new ReplayProvider(replayFile);
        List<ThreadDumpSnapshot> all = replay.loadForPid(pid.pid());
        int effectiveCount = dumpCount <= 0 ? all.size() : Math.min(dumpCount, all.size());
        List<ThreadDumpSnapshot> threadDumps = all.subList(0, effectiveCount);
        Map<String, List<CollectedData>> collectedDataByType = replay.loadCollectedDataByTypeForPid(pid.pid());
        return new LoadedTargetData(threadDumps, collectedDataByType);
    }

    private LoadedTargetData loadDataFromCollection(CommandExecutor executor, ResolvedTarget.Pid pid, Analyzer analyzer, Map<String, Object> options) throws IOException {
        Map<String, List<CollectedData>> collectedDataByType = collectAll(executor, pid.pid(), analyzer, options);
        List<CollectedData> dumpData = collectedDataByType.getOrDefault(ThreadDumpRequirement.TYPE, List.of());
        List<ThreadDumpSnapshot> threadDumps = ThreadDumpRequirement.toSnapshots(dumpData,
                extractSystemProps(collectedDataByType), SystemEnvironment.create(executor));
        if (keep && !dumpData.isEmpty()) {
            ThreadDumpRequirement.persistToDirectory(dumpData, Path.of("dumps"));
        }
        return new LoadedTargetData(threadDumps, collectedDataByType);
    }

    private LoadedTargetData loadDataForFile(ResolvedTarget.File file, Analyzer analyzer) {
        try {
            List<ThreadDumpSnapshot> threadDumps = ThreadDumpRequirement.loadFromFiles(List.of(file.path()));
            if (!validateDumpRequirement(threadDumps, analyzer)) {
                return new LoadedTargetData(new IllegalArgumentException(
                    "Analyzer " + analyzer.name() + " requires at least 2 dumps, got " + threadDumps.size()));
            }
            return new LoadedTargetData(threadDumps, Map.of());
        } catch (Exception e) {
            return new LoadedTargetData(e);
        }
    }

    private void printTargetHeader(ResolvedTarget target) {
        if (target instanceof ResolvedTarget.Pid pid) {
            System.out.println("====== PID " + pid.pid() + " (" + pid.mainClass() + ") ======");
        } else if (target instanceof ResolvedTarget.File file) {
            System.out.println("====== FILE " + file.path() + " ======");
        }
    }

    private int compareTargets(ResolvedTarget t1, ResolvedTarget t2) {
        boolean t1IsPid = t1 instanceof ResolvedTarget.Pid;
        boolean t2IsPid = t2 instanceof ResolvedTarget.Pid;

        if (t1IsPid && t2IsPid) {
            long pid1 = ((ResolvedTarget.Pid) t1).pid();
            long pid2 = ((ResolvedTarget.Pid) t2).pid();
            return Long.compare(pid1, pid2);
        } else if (t1IsPid) {
            return -1; // PIDs come before files
        } else if (t2IsPid) {
            return 1;
        } else {
            // Both are files, sort by path
            String path1 = ((ResolvedTarget.File) t1).path().toString();
            String path2 = ((ResolvedTarget.File) t2).path().toString();
            return path1.compareTo(path2);
        }
    }

    private Map<String, Object> buildOptions(int dumpCount, long intervalMs) {
        Map<String, Object> options = new HashMap<>();
        options.put("dumps", dumpCount);
        options.put("interval", intervalMs);
        options.put("keep", keep);
        if (intelligentFilter != null) {
            options.put("intelligent-filter", intelligentFilter);
        }
        options.put("full", full);
        options.putAll(getAdditionalOptions());
        return options;
    }

    /** Runs DataCollector for all requirements declared by the analyzer, returns results keyed by type. */
    private Map<String, List<CollectedData>> collectAll(CommandExecutor executor, long pid,
                                                        Analyzer analyzer, Map<String, Object> options) throws IOException {
        DataRequirements requirements = analyzer.getDataRequirements(options);
        DataCollector collector = new DataCollector(executor.diagnosticHelper(pid), requirements);
        Map<DataRequirement, List<CollectedData>> collected = collector.collectAll();

        Map<String, List<CollectedData>> byType = new HashMap<>();
        for (Map.Entry<DataRequirement, List<CollectedData>> entry : collected.entrySet()) {
            String type = entry.getKey().getType();
            if (type != null && !type.isBlank()) {
                byType.computeIfAbsent(type, __ -> new ArrayList<>()).addAll(entry.getValue());
            }
        }
        byType.replaceAll((__, samples) -> samples.stream()
                .sorted(java.util.Comparator.comparingLong(CollectedData::timestamp))
                .toList());
        return byType;
    }

    private static Map<String, String> extractSystemProps(Map<String, List<CollectedData>> byType) {
        List<CollectedData> props = byType.getOrDefault("system-properties", List.of());
        if (props.isEmpty()) return null;
        return me.bechberger.jstall.util.JcmdOutputParsers.parseVmSystemProperties(props.get(0).rawData());
    }

    private JVMDiscovery.ResolutionResult resolveTargetsFromReplay(List<String> requestedTargets) {
        try {
            ReplayProvider provider = new ReplayProvider(getEffectiveReplayFilePath());
            List<JVMDiscovery.JVMProcess> recorded = provider.listRecordedJvms(null);

            if (requestedTargets == null || requestedTargets.isEmpty()) {
                if (recorded.isEmpty()) {
                    return JVMDiscovery.ResolutionResult.error("No recorded JVMs found in replay file", false);
                }
                List<ResolvedTarget> allRecorded = recorded.stream()
                    .map(jvm -> new ResolvedTarget.Pid(jvm.pid(), jvm.mainClass()))
                    .map(t -> (ResolvedTarget) t)
                    .toList();
                return JVMDiscovery.ResolutionResult.success(allRecorded);
            }

            List<ResolvedTarget> resolved = new ArrayList<>();

            for (String target : requestedTargets) {
                if (target == null || target.isBlank()) {
                    continue;
                }

                if (target.equalsIgnoreCase("all")) {
                    if (recorded.isEmpty()) {
                        return JVMDiscovery.ResolutionResult.error("No recorded JVMs found in replay file", false);
                    }
                    resolved.addAll(recorded.stream()
                        .map(jvm -> new ResolvedTarget.Pid(jvm.pid(), jvm.mainClass()))
                        .map(t -> (ResolvedTarget) t)
                        .toList());
                    continue;
                }

                Path filePath = Path.of(target);
                if (java.nio.file.Files.exists(filePath) && java.nio.file.Files.isRegularFile(filePath)) {
                    resolved.add(new ResolvedTarget.File(filePath));
                    continue;
                }

                if (target.matches("\\d+")) {
                    long pid = Long.parseLong(target);
                    JVMDiscovery.JVMProcess match = recorded.stream()
                        .filter(jvm -> jvm.pid() == pid)
                        .findFirst()
                        .orElse(null);
                    if (match == null) {
                        return JVMDiscovery.ResolutionResult.error("No recorded JVM found with PID " + pid, false);
                    }
                    resolved.add(new ResolvedTarget.Pid(match.pid(), match.mainClass()));
                    continue;
                }

                String filter = target.toLowerCase();
                List<JVMDiscovery.JVMProcess> matches = recorded.stream()
                    .filter(jvm -> jvm.mainClass().toLowerCase().contains(filter))
                    .toList();
                if (matches.isEmpty()) {
                    return JVMDiscovery.ResolutionResult.error("No recorded JVMs found matching filter: " + target, false);
                }
                for (JVMDiscovery.JVMProcess match : matches) {
                    resolved.add(new ResolvedTarget.Pid(match.pid(), match.mainClass()));
                }
            }

            if (resolved.isEmpty()) {
                return JVMDiscovery.ResolutionResult.error("No targets specified", false);
            }
            return JVMDiscovery.ResolutionResult.success(resolved);
        } catch (IOException e) {
            return JVMDiscovery.ResolutionResult.error("Failed to open replay file: " + e.getMessage(), false);
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