package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.BaseAnalyzer;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.analyzer.ThreadActivityCategorizer;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
import me.bechberger.jthreaddump.model.LockInfo;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.model.ThreadInfo;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes thread dependencies across all dumps, building BFS dependency graphs per dump,
 * aggregating by root thread and rendering tree output showing
 * potential bottleneck roots with their blocked thread trees.
 */
public class DependencyGraphAnalyzer extends BaseAnalyzer {

    record LockDependencies(Map<ThreadInfo, Set<String>> owners, Map<ThreadInfo, Set<String>> waiters) {
    }

    record DependencyGraph(ThreadInfo root, Instant timestamp, Map<Long, ThreadInfo> nodes,
                           Map<Long, List<Long>> adjacency) {
        int size() {
            return nodes.size();
        }

        List<ThreadInfo> children(ThreadInfo parent) {
            List<Long> childIds = adjacency.getOrDefault(parent.threadId(), List.of());
            List<ThreadInfo> result = new ArrayList<>();
            for (Long id : childIds) {
                ThreadInfo t = nodes.get(id);
                if (t != null) result.add(t);
            }
            return result;
        }
    }

    record RootGroup(ThreadInfo root, List<DependencyGraph> graphs) {
    }

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
    public AnalyzerResult analyze(ResolvedData data, Map<String, Object> options) {
        List<ThreadDump> dumps = data.dumps().stream().map(ThreadDumpSnapshot::parsed).toList();
        if (dumps.isEmpty()) {
            return AnalyzerResult.nothing();
        }

        List<DependencyGraph> allGraphs = new ArrayList<>();
        for (ThreadDump dump : dumps) {
            LockDependencies dependencies = buildLockDependencies(dump.threads());
            List<ThreadInfo> roots = findRootThreads(dependencies);
            Instant ts = dump.timestamp();
            for (ThreadInfo root : roots) {
                DependencyGraph graph = buildGraph(dependencies, root, ts);
                if (graph.size() > 1) { // Only include graphs with blocked threads (more than 1 dependency)
                    allGraphs.add(graph);
                }
            }
        }

        if (allGraphs.isEmpty()) {
            return AnalyzerResult.nothing();
        }

        List<RootGroup> groups = aggregateGraphs(allGraphs);

        Map<Long, Instant> firstSeenTimes = computeFirstSeenTimes(groups);

        Map<String, Integer> heavyNodes = findHeavyNodes(groups);

        Map<Long, Set<Long>> disappearingByRoot = new HashMap<>();
        Map<Long, DependencyGraph> mergedGraphs = new HashMap<>();
        for (RootGroup group : groups) {
            DependencyGraph biggest = findBiggestGraph(group);
            Set<Long> disappeared = findDisappearingNodes(group, biggest);
            disappearingByRoot.put(group.root().threadId(), disappeared);
            mergedGraphs.put(group.root().threadId(), mergeDisappearingNodes(biggest, group, disappeared));
        }

        return AnalyzerResult.ok(formatDependencyTree(groups, mergedGraphs, disappearingByRoot,
                firstSeenTimes, heavyNodes));
    }

    LockDependencies buildLockDependencies(List<ThreadInfo> threads) {
        Map<ThreadInfo, Set<String>> owners = new HashMap<>();
        Map<ThreadInfo, Set<String>> waiters = new HashMap<>();

        for (ThreadInfo thread : threads) {
            for (LockInfo lock : thread.locks()) {
                switch (lock.operation()) {
                    case LOCKED -> owners.computeIfAbsent(thread, k -> new HashSet<>()).add(lock.lockId());
                    case WAITING_TO_LOCK, WAITING_ON, PARKING, ELIMINATED ->
                            waiters.computeIfAbsent(thread, k -> new HashSet<>()).add(lock.lockId());
                }
            }
        }
        return new LockDependencies(owners, waiters);
    }

    List<ThreadInfo> findRootThreads(LockDependencies dependencies) {
        return dependencies.owners().keySet().stream()
                .filter(owner -> !dependencies.waiters().containsKey(owner))
                .toList();
    }

    DependencyGraph buildGraph(LockDependencies dependencies, ThreadInfo root, Instant timestamp) {
        Set<Link> links = buildLinks(dependencies);

        Map<Long, ThreadInfo> nodes = new HashMap<>();
        Map<Long, List<Long>> adjacency = new HashMap<>();

        Set<Long> visited = new HashSet<>();
        Queue<ThreadInfo> queue = new ArrayDeque<>();
        queue.add(root);
        visited.add(root.threadId());

        while (!queue.isEmpty()) {
            ThreadInfo current = queue.poll();
            nodes.put(current.threadId(), current);
            adjacency.putIfAbsent(current.threadId(), new ArrayList<>());

            for (Link link : links) {
                if (Objects.equals(link.owner.threadId(), current.threadId()) && visited.add(link.waiter.threadId())) {
                    queue.add(link.waiter);
                    adjacency.computeIfAbsent(current.threadId(), k -> new ArrayList<>())
                            .add(link.waiter.threadId());
                }
            }
        }

        return new DependencyGraph(root, timestamp, nodes, adjacency);
    }

    private record Link(ThreadInfo owner, ThreadInfo waiter) {
    }

    private Set<Link> buildLinks(LockDependencies dependencies) {
        Map<String, Set<ThreadInfo>> waitersByResource = new HashMap<>();
        dependencies.waiters().forEach((thread, resources) -> {
            for (String resource : resources) {
                waitersByResource.computeIfAbsent(resource, k -> new HashSet<>()).add(thread);
            }
        });

        Set<Link> links = new LinkedHashSet<>();
        dependencies.owners().forEach((owner, resources) -> {
            for (String resource : resources) {
                Set<ThreadInfo> waiting = waitersByResource.get(resource);
                if (waiting != null) {
                    for (ThreadInfo waiter : waiting) {
                        links.add(new Link(owner, waiter));
                    }
                }
            }
        });
        return links;
    }

    List<RootGroup> aggregateGraphs(List<DependencyGraph> allGraphs) {
        Map<Long, RootGroup> byRootId = new LinkedHashMap<>();

        for (DependencyGraph graph : allGraphs) {
            long rootId = graph.root().threadId();
            byRootId.computeIfAbsent(rootId, k -> new RootGroup(graph.root(), new ArrayList<>()))
                    .graphs().add(graph);
        }

        return byRootId.values().stream()
                .sorted(Comparator.comparingInt((RootGroup rg) -> rg.graphs().size()).reversed())
                .toList();
    }

    Map<Long, Instant> computeFirstSeenTimes(List<RootGroup> groups) {
        Map<Long, Instant> firstSeen = new HashMap<>();

        for (RootGroup group : groups) {
            List<DependencyGraph> sorted = group.graphs().stream()
                    .sorted(Comparator.comparing(DependencyGraph::timestamp, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            for (DependencyGraph g : sorted) {
                for (Long threadId : g.nodes().keySet()) {
                    if (g.timestamp() != null) {
                        firstSeen.putIfAbsent(threadId, g.timestamp());
                    }
                }
            }
        }

        return firstSeen;
    }

    Map<String, Integer> findHeavyNodes(List<RootGroup> groups) {
        Map<String, Integer> counts = new LinkedHashMap<>();

        for (RootGroup group : groups) {
            if (group.graphs().isEmpty()) continue;
            DependencyGraph latest = group.graphs().get(group.graphs().size() - 1);
            for (ThreadInfo t : latest.nodes().values()) {
                counts.merge(t.name(), 1, Integer::sum);
            }
        }

        counts.entrySet().removeIf(e -> e.getValue() <= 1);
        return counts;
    }

    DependencyGraph findBiggestGraph(RootGroup group) {
        return group.graphs().stream()
                .max(Comparator.comparingInt(DependencyGraph::size))
                .orElseThrow();
    }

    /**
     * Finds threads that appear in any graph of the group but are missing in the biggest graph.
     * These are threads that were blocked at some point but disappeared.
     */
    Set<Long> findDisappearingNodes(RootGroup group, DependencyGraph biggest) {
        Set<Long> biggestNodeIds = biggest.nodes().keySet();
        Set<Long> disappeared = new LinkedHashSet<>();

        for (DependencyGraph graph : group.graphs()) {
            for (Long threadId : graph.nodes().keySet()) {
                if (!biggestNodeIds.contains(threadId)) {
                    disappeared.add(threadId);
                }
            }
        }

        DependencyGraph latest = group.graphs().get(group.graphs().size() - 1);
        Set<Long> latestNodeIds = latest.nodes().keySet();
        for (DependencyGraph graph : group.graphs()) {
            if (graph == latest) continue;
            for (Long threadId : graph.nodes().keySet()) {
                if (!latestNodeIds.contains(threadId) && !Objects.equals(threadId, group.root().threadId())) {
                    disappeared.add(threadId);
                }
            }
        }

        return disappeared;
    }

    /**
     * Adds the disappearing nodes to the biggest graph
     */
    DependencyGraph mergeDisappearingNodes(DependencyGraph biggest, RootGroup group, Set<Long> disappearedIds) {
        if (disappearedIds.isEmpty()) {
            return biggest;
        }

        Map<Long, ThreadInfo> mergedNodes = new HashMap<>(biggest.nodes());
        Map<Long, List<Long>> mergedAdj = new HashMap<>();
        for (var entry : biggest.adjacency().entrySet()) {
            mergedAdj.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        long rootId = biggest.root().threadId();
        for (Long disappearedId : disappearedIds) {
            ThreadInfo disappeared = null;
            for (DependencyGraph g : group.graphs()) {
                disappeared = g.nodes().get(disappearedId);
                if (disappeared != null) break;
            }
            if (disappeared == null) continue;

            mergedNodes.put(disappearedId, disappeared);
            mergedAdj.putIfAbsent(disappearedId, new ArrayList<>());
            mergedAdj.computeIfAbsent(rootId, k -> new ArrayList<>()).add(disappearedId);
        }

        return new DependencyGraph(biggest.root(), biggest.timestamp(), mergedNodes, mergedAdj);
    }

    private String formatDependencyTree(List<RootGroup> groups,
                                        Map<Long, DependencyGraph> mergedGraphs,
                                        Map<Long, Set<Long>> disappearingByRoot,
                                        Map<Long, Instant> firstSeenTimes,
                                        Map<String, Integer> heavyNodes) {
        StringBuilder sb = new StringBuilder();
        sb.append("Thread Dependency Graph\n");
        sb.append("======================\n\n");
        sb.append("Shows which threads are waiting on locks held by other threads.\n");

        Instant baseline = firstSeenTimes.values().stream()
                .min(Comparator.naturalOrder())
                .orElse(null);

        sb.append("Found ").append(groups.size()).append(" potential bottleneck(s)\n\n");

        for (RootGroup group : groups) {
            long rootId = group.root().threadId();
            DependencyGraph renderGraph = mergedGraphs.get(rootId);
            Set<Long> disappeared = disappearingByRoot.getOrDefault(rootId, Set.of());
            ThreadInfo root = renderGraph.root();

            sb.append(getCategoryPrefix(root)).append(" ").append(root.name());
            sb.append(" blocking threads in ").append(group.graphs().size()).append(" dump(s)");
            sb.append("\n");

            for (DependencyGraph g : group.graphs()) {
                int blockedCount = g.size() - 1;
                sb.append("  blocking ").append(blockedCount).append(" thread(s)");
                if (baseline != null && g.timestamp() != null) {
                    long timeDiff = Duration.between(baseline, g.timestamp()).getSeconds();
                    if (timeDiff > 0) {
                        sb.append(" [+").append(timeDiff).append("s]");
                    }
                }
                sb.append("\n");
            }
            sb.append("\n");

            renderTree(sb, renderGraph, root, baseline, firstSeenTimes, disappeared);
            sb.append("\n");
        }

        if (!heavyNodes.isEmpty()) {
            sb.append("Complex graph structure detected!\n");
            sb.append("Threads appearing in multiple bottleneck trees:\n");
            for (Map.Entry<String, Integer> entry : heavyNodes.entrySet()) {
                sb.append("  ").append(entry.getKey()).append(" : ").append(entry.getValue()).append("\n");
            }
            sb.append("\n");
        }

        sb.append("Summary:\n");
        sb.append("--------\n");
        sb.append("Bottleneck roots: ").append(groups.size()).append("\n");

        int totalBlocked;
        Set<String> allBlockedThreads = new HashSet<>();
        for (RootGroup group : groups) {
            DependencyGraph renderGraph = mergedGraphs.get(group.root().threadId());
            for (ThreadInfo t : renderGraph.nodes().values()) {
                if (!t.equals(renderGraph.root())) {
                    allBlockedThreads.add(t.name());
                }
            }
        }
        totalBlocked = allBlockedThreads.size();
        sb.append("Total blocked threads: ").append(totalBlocked).append("\n");

        int totalDependencies = 0;
        for (RootGroup group : groups) {
            DependencyGraph renderGraph = mergedGraphs.get(group.root().threadId());
            for (List<Long> children : renderGraph.adjacency().values()) {
                totalDependencies += children.size();
            }
        }
        sb.append("Total dependencies: ").append(totalDependencies).append("\n");

        return sb.toString();
    }

    private void renderTree(StringBuilder sb, DependencyGraph graph, ThreadInfo root,
                            Instant baseline, Map<Long, Instant> firstSeenTimes,
                            Set<Long> disappearedIds) {
        String nontrivialLocation = root.stackTrace().stream()
                .filter(e -> !e.className().contains("java.lang"))
                .map(e -> {
                    if (e.fileName() != null && e.lineNumber() != null) {
                        return e.fileName() + ":" + e.lineNumber();
                    }
                    return e.className() + "." + e.methodName();
                })
                .findFirst()
                .orElse("");

        sb.append(getCategoryPrefix(root)).append(" ").append(root.name());
        sb.append(" (").append(graph.size() - 1).append(" blocked thread(s))");
        if (!nontrivialLocation.isEmpty()) {
            sb.append(" at ").append(nontrivialLocation);
        }
        if (root.cpuTimeSec() != null) {
            sb.append(String.format(Locale.US, ", CPU: %.2fs", root.cpuTimeSec()));
        }
        sb.append("\n");

        String lockIds = root.locks().stream().map(LockInfo::lockId).collect(Collectors.joining(", "));
        if (!lockIds.isEmpty()) {
            sb.append("  locks: ").append(lockIds).append("\n");
        }

        Set<Long> visited = new HashSet<>();
        visited.add(root.threadId());

        record Frame(ThreadInfo thread, int depth) {
        }
        Deque<Frame> stack = new ArrayDeque<>();

        // Push children in reverse order so first child is popped first
        List<ThreadInfo> rootChildren = graph.children(root);
        for (int i = rootChildren.size() - 1; i >= 0; i--) {
            stack.push(new Frame(rootChildren.get(i), 1));
        }

        while (!stack.isEmpty()) {
            Frame frame = stack.pop();
            ThreadInfo current = frame.thread();
            int depth = frame.depth();

            if (!visited.add(current.threadId())) {
                continue;
            }

            String indent = "  ".repeat(depth);
            sb.append(indent);
            sb.append(getCategoryPrefix(current)).append(" ").append(current.name());

            current.stackTrace().stream()
                    .filter(e -> !e.toString().contains("lock") && !e.className().contains("java.lang"))
                    .findFirst()
                    .ifPresent(frame1 -> sb.append(" ").append(frame1));

            if (current.cpuTimeSec() != null) {
                sb.append(String.format(Locale.US, ", CPU: %.2fs", current.cpuTimeSec()));
            }

            if (baseline != null) {
                Instant firstSeen = firstSeenTimes.get(current.threadId());
                if (firstSeen != null) {
                    long timeDiff = Duration.between(baseline, firstSeen).getSeconds();
                    if (timeDiff > 0) {
                        sb.append(" [+").append(timeDiff).append("s]");
                    }
                }
            }

            if (disappearedIds.contains(current.threadId())) {
                sb.append(" [disappeared]");
            }

            sb.append("\n");

            List<ThreadInfo> children = graph.children(current);
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(new Frame(children.get(i), depth + 1));
            }
        }
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

    private String getCategoryPrefix(ThreadInfo thread) {
        ThreadActivityCategorizer.Category category = ThreadActivityCategorizer.categorize(thread);
        return "[" + category.getDisplayName() + "]";
    }
}