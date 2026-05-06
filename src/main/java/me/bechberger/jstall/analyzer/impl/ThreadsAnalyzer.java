package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.AnalyzerOutput;
import me.bechberger.jstall.analyzer.BaseAnalyzer;
import me.bechberger.jstall.analyzer.Cell;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.analyzer.TableModel;
import me.bechberger.jstall.analyzer.ThreadActivityCategorizer;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
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
        return Set.of("dump-count", "interval", "keep", "no-native", "top");
    }

    @Override
    public DumpRequirement dumpRequirement() {
        return DumpRequirement.MANY;
    }

    @Override
    public AnalyzerResult analyze(ResolvedData data, Map<String, Object> options) {
        List<ThreadDump> dumps = data.dumps().stream().map(ThreadDumpSnapshot::parsed).toList();
        boolean noNative = getNoNativeOption(options);

        // Track thread activity across dumps using base class
        Map<Long, ThreadActivity> threadActivities = trackThreadActivity(
            dumps,
            noNative,
            ThreadActivity::new
        );

        // Filter out JMX/RMI infrastructure threads injected by jstall's own connection
        threadActivities.values().removeIf(a -> isJmxInfrastructureThread(a.threadName));

        // Calculate total CPU time
        double totalCpuTimeSec = threadActivities.values().stream()
            .mapToDouble(ThreadActivity::getTotalCpuTimeSec)
            .sum();

        // Calculate elapsed time from first vs last dump using base class method
        double elapsedTimeSec = calculateElapsedTime(dumps);

        // Get top N parameter from options
        int topN = getIntOption(options, "top", -1);

        // Sort threads using base class method
        List<ThreadActivity> sortedThreads = sortThreadsByCpuTime(threadActivities.values(), topN);

        return AnalyzerResult.ok(buildOutput(sortedThreads, dumps.size(), totalCpuTimeSec, elapsedTimeSec));
    }

    private AnalyzerOutput buildOutput(List<ThreadActivity> threads, int totalDumps, double totalCpuTimeSec, double elapsedTimeSec) {
        if (threads.isEmpty()) {
            return new AnalyzerOutput.TextOutput("No threads found");
        }

        List<String> preamble = new ArrayList<>();
        preamble.add("Threads (" + totalDumps + " dumps):");

        if (totalCpuTimeSec >= 0.001) {
            StringBuilder cpuLine = new StringBuilder();
            cpuLine.append("Combined CPU time: ").append(formatCpuTime(totalCpuTimeSec));
            if (elapsedTimeSec > 0) {
                cpuLine.append(", Elapsed time: ").append(formatCpuTime(elapsedTimeSec));
                double overallUtilization = (totalCpuTimeSec * 100.0) / elapsedTimeSec;
                cpuLine.append(String.format(Locale.US, " (%.1f%% total CPU / wall-clock, sums all cores)", overallUtilization));
            }
            preamble.add(cpuLine.toString());
        }

        TableModel.Builder table = TableModel.builder()
            .setMaxCellWidth(50)
            .addColumn("THREAD", TableModel.Alignment.LEFT)
            .addColumn("CPU TIME", TableModel.Alignment.RIGHT)
            .addColumn("CPU %", TableModel.Alignment.RIGHT)
            .addColumn("STATES", TableModel.Alignment.LEFT)
            .addColumn("ACTIVITY", TableModel.Alignment.LEFT)
            .addColumn("TOP STACK FRAME", TableModel.Alignment.LEFT);

        for (ThreadActivity activity : threads) {
            double cpuTimeSec = activity.getTotalCpuTimeSec();
            String cpuTimeStr = activity.hasCpuTime()
                ? formatCpuTime(cpuTimeSec)
                : "N/A";
            double cpuPct = (activity.hasCpuTime() && totalCpuTimeSec >= 0.001)
                ? (cpuTimeSec * 100.0) / totalCpuTimeSec
                : -1;
            String cpuPercentageStr = cpuPct >= 0
                ? String.format(Locale.US, "%.1f%%", cpuPct)
                : "N/A";

            String states = activity.getStateDistribution();
            String activityDist = activity.getActivityDistribution();
            String topFrame = activity.getTopStackFrame();

            table.addRow(
                Cell.text(activity.threadName),
                activity.hasCpuTime() ? Cell.number(cpuTimeStr, cpuTimeSec) : Cell.text("N/A"),
                cpuPct >= 0 ? Cell.number(cpuPercentageStr, cpuPct, cpuColor(cpuPct)) : Cell.text("N/A"),
                Cell.text(states, stateColor(activity)),
                Cell.text(activityDist),
                Cell.text(topFrame)
            );
        }

        return new AnalyzerOutput.TableOutput(preamble, table.build());
    }


    /**
     * Formats CPU time with appropriate precision: ms for small values, s for larger.
     */
    private static String formatCpuTime(double seconds) {
        if (seconds < 0.01) {
            return String.format(Locale.US, "%.0fms", seconds * 1000.0);
        }
        return String.format(Locale.US, "%.2fs", seconds);
    }

    private static Cell.Color cpuColor(double cpuPct) {
        if (cpuPct >= 50) return Cell.Color.RED;
        if (cpuPct >= 20) return Cell.Color.YELLOW;
        if (cpuPct >= 5) return Cell.Color.GREEN;
        return null;
    }

    private static Cell.Color stateColor(ThreadActivity activity) {
        // Use dominant state for color
        Thread.State dominant = activity.stateCounts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
        if (dominant == null) return null;
        return switch (dominant) {
            case RUNNABLE -> Cell.Color.GREEN;
            case BLOCKED -> Cell.Color.RED;
            case WAITING, TIMED_WAITING -> Cell.Color.YELLOW;
            default -> null;
        };
    }

    /**
     * Returns true for JMX/RMI threads that jstall itself injects into the target JVM.
     */
    private static boolean isJmxInfrastructureThread(String name) {
        return name.startsWith("RMI TCP Connection")
            || name.startsWith("JMX server connection timeout")
            || name.startsWith("RMI Scheduler")
            || name.startsWith("RMI TCP Accept");
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
                    String topFrame = trace.get(0);
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