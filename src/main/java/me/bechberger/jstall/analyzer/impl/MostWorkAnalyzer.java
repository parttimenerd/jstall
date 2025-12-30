package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.BaseAnalyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.model.ThreadInfo;

import java.util.*;
import java.util.Locale;

/**
 * Identifies threads doing the most work across multiple dumps.
 *
 * Aggregates CPU time per thread and groups by shared stack traces.
 */
public class MostWorkAnalyzer extends BaseAnalyzer {

    @Override
    public String name() {
        return "most-work";
    }

    @Override
    public Set<String> supportedOptions() {
        return Set.of("dumps", "interval", "keep", "top", "no-native");
    }

    @Override
    public DumpRequirement dumpRequirement() {
        return DumpRequirement.MANY;
    }

    @Override
    public AnalyzerResult analyzeThreadDumps(List<ThreadDump> dumps, Map<String, Object> options) {
        int topN = getIntOption(options, "top", 3);
        boolean ignoreEmptyStacks = getBooleanOption(options, "no-native", false);

        // Track thread activity across dumps using base class
        Map<Long, ThreadActivity> threadActivities = trackThreadActivity(
            dumps,
            ignoreEmptyStacks,
            ThreadActivity::new
        );

        // Calculate total CPU time for percentage calculations
        double totalCpuTimeSec = threadActivities.values().stream()
            .mapToDouble(ThreadActivity::getTotalCpuTimeSec)
            .sum();

        // Calculate elapsed time from first vs last dump using base class method
        double elapsedTimeSec = calculateElapsedTime(dumps);

        // Sort threads using base class method
        List<ThreadActivity> topThreads = sortThreadsByCpuTime(threadActivities.values(), topN);

        return AnalyzerResult.ok(formatAsText(topThreads, dumps.size(), totalCpuTimeSec, elapsedTimeSec));
    }

    private String formatAsText(List<ThreadActivity> topThreads, int totalDumps, double totalCpuTimeSec, double elapsedTimeSec) {
        if (topThreads.isEmpty()) {
            return "No threads found";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Top threads by activity (").append(totalDumps).append(" dumps):\n");

        // Display combined metrics at the top
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

        int rank = 1;
        for (ThreadActivity activity : topThreads) {
            sb.append(rank++).append(". ")
              .append(activity.threadName).append("\n");

            // Display CPU time metrics if available
            if (activity.hasCpuTime()) {
                sb.append("   CPU time: ").append(String.format(Locale.US, "%.2fs", activity.getTotalCpuTimeSec()));

                // Display CPU percentage if there's total CPU time
                if (totalCpuTimeSec > 0) {
                    double cpuPercentage = (activity.getTotalCpuTimeSec() * 100.0) / totalCpuTimeSec;
                    sb.append(String.format(Locale.US, " (%.1f%% of total)", cpuPercentage));
                }
                sb.append("\n");

                // Display core utilization if elapsed time is available
                if (elapsedTimeSec > 0) {
                    double coreUtilization = (activity.getTotalCpuTimeSec() * 100.0) / elapsedTimeSec;
                    sb.append("   Core utilization: ").append(String.format(Locale.US, "%.1f%%", coreUtilization));

                    // Add approximate core count
                    int approxCores = (int) Math.round(coreUtilization / 100.0);
                    if (approxCores > 1) {
                        sb.append(String.format(Locale.US, " (~%d cores)", approxCores));
                    }
                    sb.append("\n");
                }
            }

            // Display state distribution
            String stateDistribution = activity.getStateDistribution();
            if (!stateDistribution.isEmpty()) {
                sb.append("   States: ").append(stateDistribution).append("\n");
            }

            // Show common stack prefix
            if (!activity.stackTraces.isEmpty()) {
                String commonStack = activity.getCommonStackPrefix();
                if (commonStack.isEmpty()) {
                    continue;
                }
                sb.append("   Common stack prefix:\n");
                String[] lines = commonStack.split("\n");
                for (int i = 0; i < Math.min(lines.length, 10); i++) {
                    sb.append("   ").append(lines[i]).append("\n");
                }
                if (lines.length > 10) {
                    sb.append("   ... (").append(lines.length - 10).append(" more lines)\n");
                }
            }
            sb.append("\n");
        }

        return sb.toString().trim();
    }

    /**
     * Tracks activity of a single thread across multiple dumps.
     */
    private static class ThreadActivity extends ThreadActivityBase {
        final List<String> stackTraces = new ArrayList<>();
        final Map<Thread.State, Integer> stateCounts = new HashMap<>();
        double maxElapsedTimeSec = 0.0;
        boolean hasElapsedTimeData = false;

        ThreadActivity(ThreadInfo thread) {
            super(thread);
        }

        @Override
        public void addOccurrence(ThreadInfo thread) {
            occurrenceCount++;

            // Track thread state
            stateCounts.put(thread.state(), stateCounts.getOrDefault(thread.state(), 0) + 1);

            // Track CPU time using base class method
            trackCpuTime(thread);

            // Track elapsed time if available
            if (thread.elapsedTimeSec() != null) {
                maxElapsedTimeSec = Math.max(maxElapsedTimeSec, thread.elapsedTimeSec());
                hasElapsedTimeData = true;
            }

            // Build stack trace string (without state, we'll show it separately)
            StringBuilder stack = new StringBuilder();

            for (var frame : thread.stackTrace()) {
                stack.append("  at ").append(frame.toString()).append("\n");
            }

            stackTraces.add(stack.toString());
        }

        double getMaxElapsedTimeSec() {
            return maxElapsedTimeSec;
        }


        boolean hasElapsedTime() {
            return hasElapsedTimeData;
        }

        String getStateDistribution() {
            if (stateCounts.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            List<Map.Entry<Thread.State, Integer>> sortedStates = stateCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .toList();

            for (int i = 0; i < sortedStates.size(); i++) {
                Map.Entry<Thread.State, Integer> entry = sortedStates.get(i);
                double percentage = (entry.getValue() * 100.0) / occurrenceCount;
                if (i > 0) sb.append(", ");
                sb.append(String.format(Locale.US, "%s: %.1f%%", entry.getKey(), percentage));
            }

            return sb.toString();
        }

        String getCommonStackPrefix() {
            if (stackTraces.isEmpty()) {
                return "";
            }

            if (stackTraces.size() == 1) {
                return stackTraces.get(0);
            }

            // Find common prefix across all stack traces
            String[] firstLines = stackTraces.get(0).split("\n");
            List<String> commonLines = new ArrayList<>();

            for (int i = 0; i < firstLines.length; i++) {
                String line = firstLines[i];
                boolean commonInAll = true;

                for (int j = 1; j < stackTraces.size(); j++) {
                    String[] otherLines = stackTraces.get(j).split("\n");
                    if (i >= otherLines.length || !otherLines[i].equals(line)) {
                        commonInAll = false;
                        break;
                    }
                }

                if (commonInAll) {
                    commonLines.add(line);
                } else {
                    break;
                }
            }

            if (commonLines.isEmpty()) {
                return stackTraces.get(0);
            }

            return String.join("\n", commonLines);
        }
    }
}