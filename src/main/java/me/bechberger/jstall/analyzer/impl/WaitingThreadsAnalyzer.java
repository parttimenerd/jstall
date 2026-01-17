package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.BaseAnalyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jthreaddump.model.LockInfo;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.model.ThreadInfo;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Identifies threads that are waiting on the same lock across multiple thread dumps with no CPU time usage.
 *
 * These threads might be starving, they are waiting without making progress.
 */
public class WaitingThreadsAnalyzer extends BaseAnalyzer {

    /** Threads that are ignored because they belong to the JVM and should not make any progress. */
    private final Set<String> IGNORED_THREAD_NAMES = Set.of(
        "Finalizer"
    );

    /**
     * Everything larger in seconds is considered CPU activity.
     */
    public static final double CPU_TIME_THRESHOLD = 0.0001;

    @Override
    public String name() {
        return "waiting-threads";
    }

    @Override
    public Set<String> supportedOptions() {
        return Set.of("dumps", "interval", "keep", "no-native", "stack-depth", "intelligent-filter");
    }

    @Override
    public DumpRequirement dumpRequirement() {
        return DumpRequirement.MANY;
    }

    @Override
    public AnalyzerResult analyzeThreadDumps(List<ThreadDump> dumps, Map<String, Object> options) {
        boolean noNative = getNoNativeOption(options);
        int stackDepth = getStackDepthOption(options);
        boolean intelligentFilter = getIntelligentFilterOption(options);

        if (dumps.isEmpty()) {
            return AnalyzerResult.ok("No thread dumps available");
        }

        final int totalDumps = dumps.size();

        // Track thread activity across dumps using base class
        Map<Long, WaitingThreadActivity> threadActivities = trackThreadActivity(
            dumps,
            noNative,
            WaitingThreadActivity::new
        );

        // Filter for threads that are waiting without CPU progress
        List<WaitingThreadActivity> waitingThreads = threadActivities.values().stream()
            .filter(activity -> !IGNORED_THREAD_NAMES.contains(activity.getThreadName()))
            .filter(activity -> isWaitingWithoutProgress(activity, totalDumps))
            .sorted((a, b) -> {
                // Sort by thread name
                return a.getThreadName().compareTo(b.getThreadName());
            })
            .collect(Collectors.toList());

        if (waitingThreads.isEmpty()) {
            return AnalyzerResult.nothing();
        }

        // Group threads by the lock object they're waiting on
        Map<String, List<WaitingThreadActivity>> threadsByLock = groupThreadsByLock(waitingThreads);

        return AnalyzerResult.ok(formatAsText(waitingThreads, threadsByLock, dumps.size(), stackDepth, intelligentFilter));
    }

    /**
     * Groups threads by the lock object they're waiting on.
     * Only groups threads that are consistently waiting on the same lock.
     */
    private Map<String, List<WaitingThreadActivity>> groupThreadsByLock(List<WaitingThreadActivity> threads) {
        Map<String, List<WaitingThreadActivity>> lockGroups = new HashMap<>();

        for (WaitingThreadActivity thread : threads) {
            String lockId = thread.getLockId();
            if (lockId != null && !lockId.isEmpty()) {
                lockGroups.computeIfAbsent(lockId, k -> new ArrayList<>()).add(thread);
            }
        }

        return lockGroups;
    }

    /**
     * Helper method to extract the lock a thread is waiting on.
     * A thread can only be waiting on one lock at a time.
     *
     * @param info The thread info to analyze
     * @return Optional containing the LockInfo if the thread is waiting on a lock, empty otherwise
     * @throws IllegalStateException if multiple "waiting on" locks are found (should not happen)
     */
    private static Optional<LockInfo> getWaitedOnLock(ThreadInfo info) {
        // First, try to get from the locks list (preferred method)
        if (info.locks() != null && !info.locks().isEmpty()) {
            List<LockInfo> locksList = info.locks().stream()
                .filter(lock -> "waiting on".equals(lock.lockType()))
                .toList();

            if (locksList.size() == 1) {
                return Optional.of(locksList.getFirst());
            } else if (locksList.size() > 1) {
                throw new IllegalStateException("Multiple locks found with 'waiting on' status for thread: " + info.name());
            }
        }

        // Fallback: use the waitingOnLock field if available
        if (info.waitingOnLock() != null && !info.waitingOnLock().isEmpty()) {
            // Create a LockInfo from the waitingOnLock string
            return Optional.of(new LockInfo(info.waitingOnLock(), null, "waiting on"));
        }

        return Optional.empty();
    }

    /**
     * Determines if a thread is waiting without making progress.
     * A thread is considered waiting without progress if:
     * 1. It appears in ALL thread dumps
     * 2. It's in WAITING or TIMED_WAITING state in ALL dumps
     * 3. It has no or minimal CPU time usage
     * 4. It's waiting on the same lock across ALL dumps (lock consistency)
     *
     * @param activity The thread activity to check
     * @param totalDumps The total number of dumps collected
     * @return true if the thread is waiting without CPU progress on the same lock in all dumps
     */
    private boolean isWaitingWithoutProgress(WaitingThreadActivity activity, int totalDumps) {
        // Thread must appear in ALL dumps
        if (activity.getOccurrenceCount() != totalDumps) {
            return false;
        }

        // Thread must be in waiting state in ALL dumps
        if (!activity.isAlwaysWaiting()) {
            return false;
        }

        // Thread should have no or minimal CPU time usage
        if (activity.hasCpuTime() && activity.getTotalCpuTimeSec() > CPU_TIME_THRESHOLD) {
            return false;
        }

        // Thread must be waiting on the same lock across ALL dumps
        return activity.isWaitingOnSameLock();
    }

    private String formatAsText(List<WaitingThreadActivity> waitingThreads,
                                Map<String, List<WaitingThreadActivity>> threadsByLock,
                                int totalDumps,
                                int stackDepth,
                                boolean intelligentFilter) {
        StringBuilder sb = new StringBuilder();

        // Header explaining what was found
        sb.append("Threads waiting on the same lock instance across ALL dumps\n");
        sb.append("with no CPU time progress (threshold: ").append(String.format(Locale.US, "%.4fs", CPU_TIME_THRESHOLD)).append(")\n");
        sb.append("\n\n");

        // Report lock contention groups (multiple threads on same lock)
        List<Map.Entry<String, List<WaitingThreadActivity>>> contentionGroups = threadsByLock.entrySet().stream()
            .filter(e -> e.getValue().size() > 1)
            .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
            .toList();

        if (!contentionGroups.isEmpty()) {
            sb.append("Multiple threads blocked on the same lock instance in all ").append(totalDumps).append(" dumps:\n\n");
            for (Map.Entry<String, List<WaitingThreadActivity>> entry : contentionGroups) {
                sb.append("Lock instance ").append(entry.getKey()).append(":\n");
                sb.append("  → ").append(entry.getValue().size()).append(" threads blocked:\n");
                for (WaitingThreadActivity thread : entry.getValue()) {
                    sb.append("    • ").append(thread.threadName).append("\n");
                }
                sb.append("\n");
            }
        }

        // Individual thread details
        sb.append("Thread Details:\n");
        sb.append("─".repeat(60)).append("\n\n");

        int rank = 1;
        for (WaitingThreadActivity activity : waitingThreads) {
            sb.append(rank++).append(". ").append(activity.threadName).append("\n");

            // Show CPU time - guaranteed to be minimal (≤ threshold) by filter
            if (activity.hasCpuTime()) {
                double cpuTime = activity.getTotalCpuTimeSec();
                sb.append("   CPU time: ").append(String.format(Locale.US, "%.4fs", cpuTime)).append("\n");
            } else {
                sb.append("   CPU time: not available\n");
            }

            // Show lock information - guaranteed to be consistent across all dumps
            String lockId = activity.getLockId();
            if (lockId != null && !lockId.isEmpty()) {
                sb.append("   Lock instance: ").append(lockId).append(" (same in all ").append(totalDumps).append(" dumps)\n");
            }

            // Show stack trace with optional intelligent filtering
            if (!activity.threadInfos.isEmpty()) {
                ThreadInfo firstThread = activity.threadInfos.getFirst();
                if (firstThread.stackTrace() != null && !firstThread.stackTrace().isEmpty()) {
                    String formatted = formatStackTraceFromFrames(
                        firstThread.stackTrace(),
                        stackDepth,
                        intelligentFilter,
                        "   ",
                        "Stack:"
                    );
                    sb.append(formatted);
                }
            }

            sb.append("\n");
        }

        sb.append("\n");
        sb.append("Summary: ").append(waitingThreads.size()).append(" thread(s) potentially starving\n");
        if (!contentionGroups.isEmpty()) {
            int totalContendingThreads = contentionGroups.stream()
                .mapToInt(e -> e.getValue().size())
                .sum();
            sb.append("         ").append(totalContendingThreads)
              .append(" thread(s) in lock contention on ")
              .append(contentionGroups.size()).append(" lock(s)\n");
        }

        return sb.toString().trim();
    }

    /**
     * Tracks waiting thread activity across multiple dumps.
     */
    private static class WaitingThreadActivity extends ThreadActivityBase {
        final Map<Thread.State, Integer> stateCounts = new HashMap<>();
        final List<String> topStackFrames = new ArrayList<>();
        final List<String> lockIds = new ArrayList<>();
        final List<String> stackTraces = new ArrayList<>();
        final List<ThreadInfo> threadInfos = new ArrayList<>();

        WaitingThreadActivity(ThreadInfo thread) {
            super(thread);
        }

        @Override
        public void addOccurrence(ThreadInfo thread) {
            occurrenceCount++;

            // Store thread info for intelligent filtering
            threadInfos.add(thread);

            // Track thread state
            stateCounts.put(thread.state(), stateCounts.getOrDefault(thread.state(), 0) + 1);

            // Track CPU time using base class method
            trackCpuTime(thread);

            // Track top stack frame for waiting location
            if (thread.stackTrace() != null && !thread.stackTrace().isEmpty()) {
                var topFrame = thread.stackTrace().getFirst();
                topStackFrames.add(topFrame.className() + "." + topFrame.methodName());

                // Build full stack trace string
                StringBuilder stack = new StringBuilder();
                for (var frame : thread.stackTrace()) {
                    stack.append(frame.toString().substring(3)).append("\n");
                }
                stackTraces.add(stack.toString());
            }

            // Track lock information using the helper method
            getWaitedOnLock(thread).ifPresent(lock -> {
                if (lock.lockId() != null && !lock.lockId().isEmpty()) {
                    lockIds.add(lock.lockId());
                }
            });
        }

        /**
         * Returns true if the thread is in WAITING or TIMED_WAITING state in ALL dumps.
         */
        boolean isAlwaysWaiting() {
            int waitingCount = stateCounts.getOrDefault(Thread.State.WAITING, 0) +
                             stateCounts.getOrDefault(Thread.State.TIMED_WAITING, 0);

            // Thread must be waiting in ALL dumps (100% consistency)
            return waitingCount == occurrenceCount;
        }

        String getTopStackFrame() {
            if (topStackFrames.isEmpty()) {
                return "";
            }

            // Find the most common top frame
            Map<String, Integer> frameCounts = new HashMap<>();
            for (String frame : topStackFrames) {
                frameCounts.put(frame, frameCounts.getOrDefault(frame, 0) + 1);
            }

            return frameCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
        }

        /**
         * Returns the lock ID this thread is waiting on.
         * Since isWaitingOnSameLock() ensures all dumps have the same lock,
         * we can safely return the first lock ID.
         */
        String getLockId() {
            if (lockIds.isEmpty()) {
                return null;
            }
            return lockIds.getFirst();
        }

        /**
         * Returns true if the thread is waiting on the same lock across ALL dumps.
         * This ensures we only flag threads that are consistently blocked on the same lock,
         * not threads that occasionally wait on different locks.
         */
        boolean isWaitingOnSameLock() {
            if (lockIds.isEmpty()) {
                // No lock information available, can't verify lock consistency
                // Don't consider it valid - we require lock information
                return false;
            }

            // Verify we have lock information for ALL occurrences
            if (lockIds.size() != occurrenceCount) {
                // Missing lock information for some dumps
                return false;
            }

            // Check if all lock IDs are the same
            String firstLockId = lockIds.getFirst();
            for (String lockId : lockIds) {
                if (!firstLockId.equals(lockId)) {
                    return false; // Different locks detected
                }
            }

            // All dumps show the same lock
            return true;
        }
    }
}