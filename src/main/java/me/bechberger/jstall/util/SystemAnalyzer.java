package me.bechberger.jstall.util;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.impl.StatusAnalyzer;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
import me.bechberger.jstall.provider.JThreadDumpProvider;
import me.bechberger.jstall.provider.ThreadDumpProvider;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.model.ThreadInfo;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Analyzes all JVMs on the system and identifies active ones.
 */
public class SystemAnalyzer {

    /**
     * Represents a JVM with its analysis and CPU usage information.
     */
    public static class JVMAnalysis {
        public final JVMDiscovery.JVMProcess process;
        public final List<ThreadDumpSnapshot> dumps;
        public final String statusAnalysis;
        public final double cpuTimeSeconds;
        public final double elapsedTimeSeconds;
        public final double cpuPercentage;

        public JVMAnalysis(JVMDiscovery.JVMProcess process,
                          List<ThreadDumpSnapshot> dumps,
                          String statusAnalysis,
                          double cpuTimeSeconds,
                          double elapsedTimeSeconds) {
            this.process = process;
            this.dumps = dumps;
            this.statusAnalysis = statusAnalysis;
            this.cpuTimeSeconds = cpuTimeSeconds;
            this.elapsedTimeSeconds = elapsedTimeSeconds;
            this.cpuPercentage = elapsedTimeSeconds > 0
                ? (cpuTimeSeconds * 100.0) / elapsedTimeSeconds
                : 0.0;
        }
    }

    private final ThreadDumpProvider provider = new JThreadDumpProvider();
    private final StatusAnalyzer statusAnalyzer = new StatusAnalyzer();

    /**
     * Analyzes all JVMs on the system.
     *
     * @param count Number of dumps per JVM
     * @param intervalMs Interval between dumps in milliseconds
     * @param options Analysis options
     * @param cpuThresholdPercent Only include JVMs using more than this % of CPU (0-100)
     * @return List of JVM analyses sorted by CPU usage (highest first)
     * @throws IOException if JVM discovery or dump collection fails
     */
    public List<JVMAnalysis> analyzeAllJVMs(int count, long intervalMs,
                                           Map<String, Object> options,
                                           double cpuThresholdPercent) throws IOException {
        // Discover all JVMs
        List<JVMDiscovery.JVMProcess> jvms = JVMDiscovery.listJVMs();

        if (jvms.isEmpty()) {
            return Collections.emptyList();
        }

        var executor = Executors.newFixedThreadPool(
                Math.min(jvms.size(), Runtime.getRuntime().availableProcessors())
        );
        try {
            // Submit all JVM analysis tasks in parallel
            List<CompletableFuture<JVMAnalysis>> futures = jvms.stream()
                .map(jvm -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return analyzeJVM(jvm, count, intervalMs, options);
                    } catch (Exception e) {
                        // Log error and return null for failed analyses
                        System.err.println("Warning: Could not analyze JVM " + jvm.pid() +
                                         " (" + jvm.mainClass() + "): " + e.getMessage());
                        return null;
                    }
                }, executor))
                .toList();

            // Wait for all analyses to complete
            CompletableFuture<Void> allOf = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
            );

            allOf.join(); // Wait for completion

            // Collect results, filter nulls and apply CPU threshold

            return futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .filter(analysis -> analysis.cpuPercentage >= cpuThresholdPercent)
                .sorted((a, b) -> Double.compare(b.cpuPercentage, a.cpuPercentage))
                .collect(Collectors.toList());

        } finally {
            executor.shutdown();
        }
    }

    /**
     * Analyzes a single JVM.
     */
    private JVMAnalysis analyzeJVM(JVMDiscovery.JVMProcess jvm, int count, long intervalMs,
                                   Map<String, Object> options) throws IOException {
        // Collect dumps
        List<ThreadDumpSnapshot> dumps = provider.collectFromJVM(jvm.pid(), count, intervalMs, null);

        // Run status analysis
        AnalyzerResult statusResult = statusAnalyzer.analyze(dumps, options);
        String analysis = statusResult.output();

        // Calculate CPU time and elapsed time
        double cpuTime = calculateTotalCpuTime(dumps);
        double elapsedTime = calculateElapsedTime(dumps);

        return new JVMAnalysis(jvm, dumps, analysis, cpuTime, elapsedTime);
    }

    /**
     * Calculates total CPU time across all threads in all dumps.
     */
    private double calculateTotalCpuTime(List<ThreadDumpSnapshot> dumps) {
        double totalCpuTime = 0.0;

        for (ThreadDumpSnapshot dumpWithRaw : dumps) {
            ThreadDump dump = dumpWithRaw.parsed();
            for (ThreadInfo thread : dump.threads()) {
                if (thread.cpuTimeSec() != null) {
                    totalCpuTime += thread.cpuTimeSec();
                }
            }
        }

        return totalCpuTime;
    }

    /**
     * Calculates elapsed time between first and last dump.
     */
    private double calculateElapsedTime(List<ThreadDumpSnapshot> dumps) {
        if (dumps.size() < 2) {
            return 0.0;
        }

        ThreadDump first = dumps.get(0).parsed();
        ThreadDump last = dumps.get(dumps.size() - 1).parsed();

        if (first.timestamp() == null || last.timestamp() == null) {
            return 0.0;
        }

        return java.time.Duration.between(first.timestamp(), last.timestamp()).toMillis() / 1000.0;
    }

    /**
     * Formats a summary of all JVM analyses.
     */
    public static String formatSystemSummary(List<JVMAnalysis> analyses) {
        StringBuilder sb = new StringBuilder();
        sb.append("System-wide JVM Analysis\n");
        sb.append("=".repeat(60)).append("\n\n");
        sb.append(String.format("Found %d active JVM(s):\n\n", analyses.size()));

        for (int i = 0; i < analyses.size(); i++) {
            JVMAnalysis analysis = analyses.get(i);
            sb.append(String.format("%d. PID %d - %s\n",
                i + 1, analysis.process.pid(), analysis.process.mainClass()));
            sb.append(String.format("   CPU Usage: %.2fs (%.1f%% of interval)\n",
                analysis.cpuTimeSeconds, analysis.cpuPercentage));
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Formats detailed analysis for all JVMs.
     */
    public static String formatDetailedAnalyses(List<JVMAnalysis> analyses) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < analyses.size(); i++) {
            JVMAnalysis analysis = analyses.get(i);

            if (i > 0) {
                sb.append("\n\n");
                sb.append("=".repeat(80)).append("\n\n");
            }

            sb.append(String.format("JVM %d: PID %d - %s\n",
                i + 1, analysis.process.pid(), analysis.process.mainClass()));
            sb.append(String.format("CPU: %.2fs (%.1f%% of interval)\n\n",
                analysis.cpuTimeSeconds, analysis.cpuPercentage));
            sb.append(analysis.statusAnalysis);
        }

        return sb.toString();
    }
}