package me.bechberger.jstall.analyzer;

import me.bechberger.jstall.model.ThreadDumpSnapshot;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.model.ThreadInfo;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Base class for analyzers providing common option handling utilities.
 *
 * Default values for options should be provided by the command classes (e.g., BaseAnalyzerCommand),
 * but analyzers can also handle missing values gracefully for testing purposes.
 */
public abstract class BaseAnalyzer implements Analyzer {

    /**
     * Retrieves a boolean option from the options map with a default value.
     *
     * @param options The options map
     * @param key The option key
     * @param defaultValue The default value if key is missing
     * @return The option value or default
     */
    protected boolean getBooleanOption(Map<String, Object> options, String key, boolean defaultValue) {
        Object value = options.get(key);
        return value instanceof Boolean ? (Boolean) value : defaultValue;
    }

    /**
     * Retrieves an integer option from the options map with a default value.
     *
     * @param options The options map
     * @param key The option key
     * @param defaultValue The default value if key is missing
     * @return The option value or default
     */
    protected int getIntOption(Map<String, Object> options, String key, int defaultValue) {
        Object value = options.get(key);
        return value instanceof Integer ? (Integer) value : defaultValue;
    }

    /**
     * Retrieves a string option from the options map with a default value.
     *
     * @param options The options map
     * @param key The option key
     * @param defaultValue The default value if key is missing
     * @return The option value or default
     */
    protected String getStringOption(Map<String, Object> options, String key, String defaultValue) {
        Object value = options.get(key);
        return value instanceof String ? (String) value : defaultValue;
    }

    protected boolean getNoNativeOption(Map<String, Object> options) {
        return getBooleanOption(options, "no-native", false);
    }

    protected int getStackDepthOption(Map<String, Object> options) {
        return getIntOption(options, "stack-depth", 20);
    }

    protected boolean getIntelligentFilterOption(Map<String, Object> options) {
        return getBooleanOption(options, "intelligent-filter", false);
    }

    /**
     * Tracks thread activity across multiple dumps.
     *
     * @param dumps The thread dumps to analyze
     * @param noNative If true, skip threads without stack traces
     * @param activityFactory Factory function to create ThreadActivity instances
     * @param <T> The type of ThreadActivity
     * @return Map of thread ID to activity
     */
    protected <T extends ThreadActivityBase> Map<Long, T> trackThreadActivity(
            List<ThreadDump> dumps,
            boolean noNative,
            java.util.function.Function<ThreadInfo, T> activityFactory) {

        Map<Long, T> threadActivities = new HashMap<>();

        for (ThreadDump dump : dumps) {
            for (ThreadInfo thread : dump.threads()) {
                // Skip threads without stack traces if no-native is enabled
                if (noNative && (thread.stackTrace() == null || thread.stackTrace().isEmpty())) {
                    continue;
                }

                Long threadId = thread.threadId();
                if (threadId == null) {
                    // Skip threads without IDs
                    continue;
                }

                T activity = threadActivities.computeIfAbsent(threadId, id -> activityFactory.apply(thread));
                activity.addOccurrence(thread);
            }
        }

        return threadActivities;
    }

    /**
     * Sorts thread activities by CPU time (descending), with fallback to other criteria.
     *
     * @param activities Collection of thread activities
     * @param topN Maximum number of threads to return (-1 for all)
     * @param <T> The type of ThreadActivity
     * @return Sorted list of thread activities
     */
    protected <T extends ThreadActivityBase> List<T> sortThreadsByCpuTime(
            Collection<T> activities,
            int topN) {

        return activities.stream()
            .sorted((a, b) -> {
                // Primary: Sort by CPU time if both have it
                if (a.hasCpuTime() && b.hasCpuTime()) {
                    int cpuCompare = Double.compare(b.getTotalCpuTimeSec(), a.getTotalCpuTimeSec());
                    if (cpuCompare != 0) return cpuCompare;
                }

                // Secondary: Threads with CPU time come first
                if (a.hasCpuTime() != b.hasCpuTime()) {
                    return a.hasCpuTime() ? -1 : 1;
                }

                // Tertiary: Sort by thread name for stability
                return a.getThreadName().compareTo(b.getThreadName());
            })
            .limit(topN != -1 ? topN : Integer.MAX_VALUE)
            .collect(Collectors.toList());
    }

    /**
     * Calculates the elapsed time between first and last thread dump.
     *
     * @param dumps The list of thread dumps
     * @return Elapsed time in seconds, or 0.0 if less than 2 dumps
     */
    protected double calculateElapsedTime(List<ThreadDump> dumps) {
        if (dumps.size() < 2) {
            return 0.0;
        }
        // make more robust by using the timestamps from the dumps directly
        long firstTimestamp = dumps.get(0).timestamp().toEpochMilli();
        long lastTimestamp = dumps.get(dumps.size() - 1).timestamp().toEpochMilli();
        return (lastTimestamp - firstTimestamp) / 1000.0;
    }

    /**
     * Formats a stack trace for display with configurable depth.
     *
     * @param stackTrace The stack trace string (newline-separated)
     * @param maxDepth Maximum number of stack frames to show (0 = all, 1 = inline single line)
     * @param indent Indentation prefix for each line
     * @param label Label to display before the stack trace (e.g., "Common stack prefix:", "Stack trace:")
     * @return Formatted stack trace string, or empty string if stackTrace is empty
     */
    protected String formatStackTrace(String stackTrace, int maxDepth, String indent, String label) {
        if (stackTrace == null || stackTrace.isEmpty()) {
            return "";
        }

        String[] lines = stackTrace.split("\n");
        if (lines.length == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        if (maxDepth == 1 && lines.length > 0) {
            // Inline mode: show only first line on same line as label
            sb.append(indent).append(label).append(" ").append(lines[0].trim()).append("\n");
        } else {
            // Multi-line mode
            sb.append(indent).append(label).append("\n");
            int linesToShow = maxDepth > 0 ? Math.min(lines.length, maxDepth) : lines.length;

            for (int i = 0; i < linesToShow; i++) {
                sb.append(indent).append("  ").append(lines[i]).append("\n");
            }

            if (maxDepth > 0 && lines.length > maxDepth) {
                sb.append(indent).append("  ").append("... (").append(lines.length - maxDepth).append(" more lines)\n");
            }
        }

        return sb.toString();
    }

    /**
     * Formats a stack trace from StackFrame list with optional intelligent filtering.
     *
     * @param frames The stack frames
     * @param maxDepth Maximum number of frames to show (in intelligent mode: max relevant frames)
     * @param intelligentFilter Whether to use intelligent filtering
     * @param indent Indentation prefix for each line
     * @param label Label to display before the stack trace
     * @return Formatted stack trace string, or empty string if no frames
     */
    protected String formatStackTraceFromFrames(List<me.bechberger.jthreaddump.model.StackFrame> frames,
                                                int maxDepth,
                                                boolean intelligentFilter,
                                                String indent,
                                                String label) {
        if (frames == null || frames.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(indent).append(label).append("\n");

        if (intelligentFilter) {
            // Use intelligent filtering
            List<IntelligentStackFilter.FilteredFrame> filtered =
                IntelligentStackFilter.filterStackTrace(frames, maxDepth);
            sb.append(IntelligentStackFilter.formatFilteredStackTrace(filtered, indent + "  "));
        } else {
            // Simple depth-based filtering
            int linesToShow = maxDepth > 0 ? Math.min(frames.size(), maxDepth) : frames.size();
            for (int i = 0; i < linesToShow; i++) {
                sb.append(indent).append("  ").append(frames.get(i).toString()).append("\n");
            }
            if (maxDepth > 0 && frames.size() > maxDepth) {
                sb.append(indent).append("  ").append("... (").append(frames.size() - maxDepth).append(" more frames)\n");
            }
        }

        return sb.toString();
    }

    /**
     * Base class for tracking thread activity across dumps.
     */
    protected abstract static class ThreadActivityBase {
        public final String threadName;
        public final Long threadId;
        protected int occurrenceCount = 0;
        protected Double firstCpuTimeSec = null;
        protected Double lastCpuTimeSec = null;
        protected boolean hasCpuTimeData = false;

        protected ThreadActivityBase(ThreadInfo thread) {
            this.threadName = thread.name();
            this.threadId = thread.threadId();
        }

        /**
         * Records an occurrence of this thread in a dump.
         */
        public abstract void addOccurrence(ThreadInfo thread);

        /**
         * Returns the total CPU time consumed during the observation period.
         */
        public double getTotalCpuTimeSec() {
            if (firstCpuTimeSec != null && lastCpuTimeSec != null) {
                return lastCpuTimeSec - firstCpuTimeSec;
            }
            return 0.0;
        }

        /**
         * Returns true if this thread has CPU time data.
         */
        public boolean hasCpuTime() {
            return hasCpuTimeData;
        }

        /**
         * Returns the thread name.
         */
        public String getThreadName() {
            return threadName;
        }

        /**
         * Returns the number of times this thread appeared in dumps.
         */
        public int getOccurrenceCount() {
            return occurrenceCount;
        }

        /**
         * Updates CPU time tracking with a new thread info.
         */
        protected void trackCpuTime(ThreadInfo thread) {
            if (thread.cpuTimeSec() != null) {
                if (firstCpuTimeSec == null) {
                    firstCpuTimeSec = thread.cpuTimeSec();
                }
                lastCpuTimeSec = thread.cpuTimeSec();
                hasCpuTimeData = true;
            }
        }
    }

    public static void assertSortedByDate(List<ThreadDumpSnapshot> dumps) {
        for (int i = 1; i < dumps.size(); i++) {
            if (dumps.get(i).parsed().timestamp().isBefore(dumps.get(i - 1).parsed().timestamp())) {
                throw new IllegalArgumentException("Thread dumps are not sorted by date");
            }
        }
    }
}