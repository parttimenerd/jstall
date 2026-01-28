package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.BaseAnalyzer;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.analyzer.ThreadActivityCategorizer;
import me.bechberger.jthreaddump.model.LockInfo;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.model.ThreadInfo;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes thread dependencies by showing which threads wait on locks held by other threads.
 * Creates a dependency graph showing the waiting relationships.
 */
public class DependencyGraphAnalyzer extends BaseAnalyzer {

    @Override
    public String name() {
        return "dependency-graph";
    }

    @Override
    public Set<String> supportedOptions() {
        return Set.of("dumps", "interval", "keep");
    }

    @Override
    public DumpRequirement dumpRequirement() {
        return DumpRequirement.ANY;
    }

    @Override
    public AnalyzerResult analyzeThreadDumps(List<ThreadDump> dumps, Map<String, Object> options) {
        // Use the latest dump for dependency analysis
        ThreadDump latestDump = dumps.get(dumps.size() - 1);

        // Build lock ownership map
        Map<String, ThreadInfo> lockOwners = new HashMap<>();
        Map<ThreadInfo, String> threadWaitingOn = new HashMap<>();

        for (ThreadInfo thread : latestDump.threads()) {
            // Track locks held by this thread
            if (thread.locks() != null) {
                for (LockInfo lock : thread.locks()) {
                    if (lock.isLocked()) {
                        lockOwners.put(lock.lockId(), thread);
                    }
                }
            }

            // Track what lock this thread is waiting on
            getWaitedOnLock(thread).ifPresent(lock -> threadWaitingOn.put(thread, lock.lockId()));
        }

        // Build dependency graph
        Map<ThreadInfo, Set<ThreadInfo>> dependencies = new HashMap<>();
        Map<ThreadInfo, String> waitReasons = new HashMap<>();

        for (Map.Entry<ThreadInfo, String> entry : threadWaitingOn.entrySet()) {
            ThreadInfo waiter = entry.getKey();
            String lockId = entry.getValue();
            ThreadInfo owner = lockOwners.get(lockId);

            if (owner != null && !owner.equals(waiter)) {
                dependencies.computeIfAbsent(waiter, k -> new HashSet<>()).add(owner);
                waitReasons.put(waiter, lockId);
            }
        }
        if (dependencies.isEmpty()) {
            return AnalyzerResult.nothing();
        }
        return AnalyzerResult.ok(formatDependencyGraph(dependencies, waitReasons, latestDump));
    }

    public Optional<LockInfo> getWaitedOnLock(ThreadInfo thread) {
        List<LockInfo> locksList = thread.locks().stream().filter(LockInfo::isBlocking).toList();
        if (locksList.isEmpty()) {
            return Optional.empty();
        }
        if (locksList.size() == 1) {
            return Optional.of(locksList.get(0));
        }
        return Optional.of(pickTopBlockingLock(locksList));
    }

    /**
     * Some thread dumps (notably JVM service threads like {@code Finalizer}) can report multiple blocking locks.
     * For status reporting we pick a deterministic "top" lock instead of failing.
     */
    private LockInfo pickTopBlockingLock(List<LockInfo> blockingLocks) {
        return blockingLocks.stream()
            .min(Comparator
                // Prefer a real monitor-enter block over other kinds of blocking.
                .comparingInt((LockInfo l) -> blockingLockPriority(l.operation()))
                // Then tie-break deterministically.
                .thenComparing(LockInfo::lockId, Comparator.nullsLast(String::compareTo))
                .thenComparing(LockInfo::className, Comparator.nullsLast(String::compareTo)))
            .orElseThrow();
    }

    private int blockingLockPriority(LockInfo.LockOperation op) {
        if (op == null) {
            return 10;
        }
        return switch (op) {
            case WAITING_TO_LOCK -> 0;
            case PARKING, WAITING_ON -> 1;
            default -> 5;
        };
    }

    private String formatDependencyGraph(Map<ThreadInfo, Set<ThreadInfo>> dependencies,
                                        Map<ThreadInfo, String> waitReasons,
                                        ThreadDump dump) {

        StringBuilder sb = new StringBuilder();
        sb.append("Thread Dependency Graph\n");
        sb.append("======================\n\n");
        sb.append("Shows which threads are waiting on locks held by other threads.\n");
        sb.append("Format: [Category] Thread Name → [Category] Owner Thread Name (lock: <lockId>)\n\n");

        // Sort by thread name for consistent output
        List<Map.Entry<ThreadInfo, Set<ThreadInfo>>> sortedDeps = dependencies.entrySet().stream()
            .sorted(Comparator.comparing(e -> e.getKey().name()))
            .toList();

        for (Map.Entry<ThreadInfo, Set<ThreadInfo>> entry : sortedDeps) {
            ThreadInfo waiter = entry.getKey();
            Set<ThreadInfo> owners = entry.getValue();

            String waiterCategory = getCategoryPrefix(waiter);
            String lockId = waitReasons.get(waiter);

            for (ThreadInfo owner : owners) {
                String ownerCategory = getCategoryPrefix(owner);

                sb.append(waiterCategory)
                  .append(" ")
                  .append(waiter.name())
                  .append("\n  → ")
                  .append(ownerCategory)
                  .append(" ")
                  .append(owner.name());

                if (lockId != null) {
                    sb.append(" (lock: <").append(lockId).append(">)");
                }

                sb.append("\n");

                // Show thread states
                sb.append("     Waiter state: ").append(waiter.state());
                if (waiter.cpuTimeSec() != null) {
                    sb.append(String.format(Locale.US, ", CPU: %.2fs", waiter.cpuTimeSec()));
                }
                sb.append("\n");

                sb.append("     Owner state:  ").append(owner.state());
                if (owner.cpuTimeSec() != null) {
                    sb.append(String.format(Locale.US, ", CPU: %.2fs", owner.cpuTimeSec()));
                }
                sb.append("\n\n");
            }
        }

        // Add summary statistics
        sb.append("\nSummary:\n");
        sb.append("--------\n");
        sb.append("Total waiting threads: ").append(dependencies.size()).append("\n");

        int totalDependencies = dependencies.values().stream()
            .mapToInt(Set::size)
            .sum();
        sb.append("Total dependencies: ").append(totalDependencies).append("\n");

        // Find chains (threads waiting on threads that are also waiting)
        Set<ThreadInfo> chainedThreads = new HashSet<>();
        for (ThreadInfo waiter : dependencies.keySet()) {
            if (dependencies.containsKey(waiter)) {
                for (ThreadInfo owner : dependencies.get(waiter)) {
                    if (dependencies.containsKey(owner)) {
                        chainedThreads.add(waiter);
                        chainedThreads.add(owner);
                    }
                }
            }
        }

        if (!chainedThreads.isEmpty()) {
            sb.append("\nDependency Chains Detected:\n");
            sb.append("---------------------------\n");
            sb.append("Threads involved in chains: ").append(chainedThreads.size()).append("\n");

            // Show chains
            Set<ThreadInfo> visited = new HashSet<>();
            for (ThreadInfo start : dependencies.keySet()) {
                if (!visited.contains(start)) {
                    List<ThreadInfo> chain = buildChain(start, dependencies, visited);
                    if (chain.size() > 1) {
                        sb.append("\nChain: ");
                        for (int i = 0; i < chain.size(); i++) {
                            if (i > 0) sb.append(" → ");
                            sb.append(getCategoryPrefix(chain.get(i)))
                              .append(" ")
                              .append(chain.get(i).name());
                        }
                        sb.append("\n");
                    }
                }
            }
        }

        return sb.toString();
    }

    private List<ThreadInfo> buildChain(ThreadInfo start, Map<ThreadInfo, Set<ThreadInfo>> dependencies, Set<ThreadInfo> visited) {
        List<ThreadInfo> chain = new ArrayList<>();
        ThreadInfo current = start;
        Set<ThreadInfo> chainVisited = new HashSet<>();

        while (current != null && !chainVisited.contains(current)) {
            chain.add(current);
            visited.add(current);
            chainVisited.add(current);

            Set<ThreadInfo> owners = dependencies.get(current);
            if (owners != null && !owners.isEmpty()) {
                current = owners.iterator().next(); // Follow first owner
            } else {
                current = null;
            }
        }

        return chain;
    }

    private String getCategoryPrefix(ThreadInfo thread) {
        ThreadActivityCategorizer.Category category = ThreadActivityCategorizer.categorize(thread);
        return "[" + category.getDisplayName() + "]";
    }
}