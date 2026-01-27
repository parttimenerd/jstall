package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.BaseAnalyzer;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.analyzer.ThreadActivityCategorizer;
import me.bechberger.jstall.util.TablePrinter;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.model.ThreadInfo;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzer that lists all threads sorted by CPU time in a table format.
 */
public class ThreadsAnalyzer extends BaseAnalyzer {

    @Override
    public String name() {
        return "threads";
    }

    @Override
    public Set<String> supportedOptions() {
        return Set.of("dumps", "interval", "keep", "no-native");
    }

    @Override
    public DumpRequirement dumpRequirement() {
        return DumpRequirement.MANY;
    }

    @Override
    public AnalyzerResult analyzeThreadDumps(List<ThreadDump> dumps, Map<String, Object> options) {
        boolean noNative = getNoNativeOption(options);

        // Track thread activity across dumps using base class
        Map<Long, ThreadActivity> threadActivities = trackThreadActivity(
            dumps,
            noNative,
            ThreadActivity::new
        );

        // Calculate total CPU time
        double totalCpuTimeSec = threadActivities.values().stream()
            .mapToDouble(ThreadActivity::getTotalCpuTimeSec)
            .sum();

        // Calculate elapsed time from first vs last dump using base class method
        double elapsedTimeSec = calculateElapsedTime(dumps);

        // Sort threads using base class method
        List<ThreadActivity> sortedThreads = sortThreadsByCpuTime(threadActivities.values(), -1);

        return AnalyzerResult.ok(formatAsTable(sortedThreads, dumps.size(), totalCpuTimeSec, elapsedTimeSec));
    }

    /**
     * Formats the thread list as a table.
     */
    private String formatAsTable(List<ThreadActivity> threads, int totalDumps, double totalCpuTimeSec, double elapsedTimeSec) {
        if (threads.isEmpty()) {
            return "No threads found";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Threads (").append(totalDumps).append(" dumps):\n");

        // Display overall CPU time and elapsed time at the top
        if (totalCpuTimeSec > 0) {
            sb.append("Combined CPU time: ").append(String.format(Locale.US, "%.2fs", totalCpuTimeSec));
            if (elapsedTimeSec > 0) {
                sb.append(", Elapsed time: ").append(String.format(Locale.US, "%.2fs", elapsedTimeSec));
                double overallUtilization = (totalCpuTimeSec * 100.0) / elapsedTimeSec;
                sb.append(String.format(Locale.US, " (%.1f%% overall utilization)", overallUtilization));
            }
            sb.append("\n");
        }
        sb.append("\n");

        // Create table
        TablePrinter table = new TablePrinter()
            .setMaxCellWidth(50)
            .addColumn("THREAD", TablePrinter.Alignment.LEFT)
            .addColumn("CPU TIME", TablePrinter.Alignment.RIGHT)
            .addColumn("CPU %", TablePrinter.Alignment.RIGHT)
            .addColumn("STATES", TablePrinter.Alignment.LEFT)
            .addColumn("ACTIVITY", TablePrinter.Alignment.LEFT)
            .addColumn("TOP STACK FRAME", TablePrinter.Alignment.LEFT);

        // Add rows
        for (ThreadActivity activity : threads) {
            String cpuTime = activity.hasCpuTime()
                ? String.format(Locale.US, "%.2fs", activity.getTotalCpuTimeSec())
                : "N/A";
            String cpuPercentage = (activity.hasCpuTime() && totalCpuTimeSec > 0)
                ? String.format(Locale.US, "%.1f%%", (activity.getTotalCpuTimeSec() * 100.0) / totalCpuTimeSec)
                : "N/A";

            String states = activity.getStateDistribution();
            String activityDist = activity.getActivityDistribution();
            String topFrame = activity.getTopStackFrame();

            table.addRow(
                activity.threadName,
                cpuTime,
                cpuPercentage,
                states,
                activityDist,
                topFrame
            );
        }

        sb.append(table.render());
        return sb.toString();
    }


    /**
     * Tracks activity of a single thread across multiple dumps.
     */
    private static class ThreadActivity extends ThreadActivityBase {
        final List<List<String>> stackTraces = new ArrayList<>();
        final List<ThreadInfo> threadInfos = new ArrayList<>();
        final Map<Thread.State, Integer> stateCounts = new HashMap<>();
        double maxElapsedTimeSec = 0.0;

        ThreadActivity(ThreadInfo thread) {
            super(thread);
        }

        @Override
        public void addOccurrence(ThreadInfo thread) {
            occurrenceCount++;

            // Track thread info for activity categorization
            threadInfos.add(thread);

            // Track thread state
            stateCounts.put(thread.state(), stateCounts.getOrDefault(thread.state(), 0) + 1);

            // Track CPU time using base class method
            trackCpuTime(thread);

            // Track elapsed time if available
            if (thread.elapsedTimeSec() != null) {
                maxElapsedTimeSec = Math.max(maxElapsedTimeSec, thread.elapsedTimeSec());
            }

            // Store stack frames
            List<String> frames = thread.stackTrace().stream()
                .map(frame -> frame.className() + "." + frame.methodName())
                .collect(Collectors.toList());
            stackTraces.add(frames);
        }

        String getStateDistribution() {
            if (stateCounts.isEmpty()) {
                return "";
            }

            List<Map.Entry<Thread.State, Integer>> sortedStates = stateCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .toList();

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < sortedStates.size(); i++) {
                Map.Entry<Thread.State, Integer> entry = sortedStates.get(i);
                double percentage = (entry.getValue() * 100.0) / occurrenceCount;
                if (i > 0) sb.append(", ");
                String state = entry.getKey() != null ? entry.getKey().name() : "";
                if (percentage == 100.0 || state.isEmpty()) {
                    sb.append(state);
                } else {
                    sb.append(String.format(Locale.US, "%s: %.0f%%", entry.getKey().name(), percentage));
                }
            }

            return sb.toString();
        }

        String getActivityDistribution() {
            if (threadInfos.isEmpty()) {
                return "";
            }

            Map<ThreadActivityCategorizer.Category, Integer> distribution =
                ThreadActivityCategorizer.categorizeMultiple(threadInfos);
            return ThreadActivityCategorizer.formatDistribution(distribution, threadInfos.size());
        }

        String getTopStackFrame() {
            if (stackTraces.isEmpty()) {
                return "";
            }

            // Find the most common top frame across all stack traces
            Map<String, Integer> frameCounts = new HashMap<>();
            for (List<String> trace : stackTraces) {
                if (!trace.isEmpty()) {
                    String topFrame = trace.getFirst();
                    frameCounts.put(topFrame, frameCounts.getOrDefault(topFrame, 0) + 1);
                }
            }

            if (frameCounts.isEmpty()) {
                return "";
            }

            return frameCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
        }
    }
}