package me.bechberger.jstall.provider;

import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jstall.provider.requirement.DataRequirement;
import me.bechberger.jstall.provider.requirement.DataRequirements;
import me.bechberger.jstall.provider.requirement.IntervalWindowRequirement;
import me.bechberger.jstall.provider.requirement.JcmdRequirement;
import me.bechberger.jstall.util.JMXDiagnosticHelper;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Orchestrates collection of data requirements from a JVM.
 * Handles timing, parallelism, and scheduling of data collection operations.
 */
public class DataCollector {

    private final JMXDiagnosticHelper helper;
    private final DataRequirements requirements;
    private final ScheduledExecutorService scheduler;
    private final boolean ownScheduler;
    private final boolean verbose;
    private static final long BETWEEN_SAMPLE_SAFETY_MARGIN_MS = 200;

    public DataCollector(JMXDiagnosticHelper helper, DataRequirements requirements) {
        this(helper, requirements, null, false);
    }

    public DataCollector(JMXDiagnosticHelper helper, DataRequirements requirements,
                        ScheduledExecutorService scheduler) {
        this(helper, requirements, scheduler, false);
    }

    public DataCollector(JMXDiagnosticHelper helper, DataRequirements requirements,
                        ScheduledExecutorService scheduler, boolean verbose) {
        this.helper = helper;
        this.requirements = requirements;
        this.verbose = verbose;
        if (scheduler == null) {
            this.scheduler = Executors.newScheduledThreadPool(2);
            this.ownScheduler = true;
        } else {
            this.scheduler = scheduler;
            this.ownScheduler = false;
        }
    }

    /**
     * Collects all data according to the requirements.
     * Groups requirements by interval for synchronized collection.
     *
     * @return Map of requirement -&gt; collected data samples
     * @throws IOException if collection fails
     */
    public Map<DataRequirement, List<CollectedData>> collectAll() throws IOException {
        Map<DataRequirement, List<CollectedData>> results = new ConcurrentHashMap<>();

        try {
            // Group requirements by schedule
            Map<Long, List<DataRequirement>> byInterval = requirements.getRequirements().stream()
                .collect(Collectors.groupingBy(r -> r.getSchedule().intervalMs()));

            // Separate one-time from interval-based
            List<DataRequirement> oneTime = byInterval.getOrDefault(0L, List.of());
            Map<Long, List<DataRequirement>> intervals = byInterval.entrySet().stream()
                .filter(e -> e.getKey() > 0)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            if (verbose) {
                System.out.println("    Collecting " + oneTime.size() + " one-time requirement(s)");
            }

            // Batch-prefetch all one-time JcmdRequirements in a single SSH call
            prefetchJcmd(oneTime);

            // Collect one-time data first (system properties, etc.)
            for (DataRequirement req : oneTime) {
                if (verbose) {
                    System.out.println("      " + req.getDescription());
                }
                List<CollectedData> samples = new ArrayList<>();
                try {
                    samples.add(req.collect(helper, 0));
                } catch (IOException e) {
                    if (verbose) {
                        System.err.println("      Failed: " + e.getMessage());
                    }
                    samples.add(new CollectedData(
                        System.currentTimeMillis(),
                        "",
                        Map.of("error", e.getMessage())
                    ));
                }
                results.put(req, samples);
            }

            // Collect interval-based data
            if (!intervals.isEmpty()) {
                if (verbose) {
                    System.out.println("    Collecting interval-based requirements");
                }
                collectWithIntervals(intervals, results);
            }

            return results;

        } finally {
            if (ownScheduler) {
                scheduler.shutdown();
                try {
                    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        scheduler.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    scheduler.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Prefetches all {@link JcmdRequirement}s in the list in a single SSH call,
     * populating the helper's cache so subsequent {@code collect()} calls are instant.
     * Non-JcmdRequirements and local (non-remote) helpers are silently skipped.
     */
    private void prefetchJcmd(List<DataRequirement> reqs) {
        List<Map.Entry<String, String[]>> commands = reqs.stream()
            .filter(r -> r instanceof JcmdRequirement)
            .map(r -> (JcmdRequirement) r)
            .map(r -> Map.entry(r.getCommand(), r.getArgs() != null ? r.getArgs() : new String[0]))
            .collect(Collectors.toList());
        if (commands.isEmpty()) return;
        try {
            helper.prefetchJcmd(commands);
        } catch (IOException e) {
            // Non-fatal: fall through to per-command execution
            if (verbose) {
                System.err.println("    [batch-prefetch] failed, falling back to sequential: " + e.getMessage());
            }
        }
    }

    /**
     * Collects data for requirements with intervals.
     * Synchronizes collection so requirements with the same interval are collected together.
     */
    private void collectWithIntervals(Map<Long, List<DataRequirement>> byInterval,
                                     Map<DataRequirement, List<CollectedData>> results)
            throws IOException {
        List<Exception> exceptions = new ArrayList<>();

        for (Map.Entry<Long, List<DataRequirement>> entry : byInterval.entrySet()) {
            long intervalMs = entry.getKey();
            List<DataRequirement> reqs = entry.getValue();

            for (DataRequirement req : reqs) {
                results.computeIfAbsent(req, __ -> new ArrayList<>());
            }

            int maxCount = reqs.stream()
                .mapToInt(r -> r.getSchedule().count())
                .max()
                .orElse(1);

            for (int sampleIndex = 0; sampleIndex < maxCount; sampleIndex++) {
                long cycleStart = System.currentTimeMillis();
                final int si = sampleIndex;

                // Batch-prefetch all JcmdRequirements due for this sample round
                List<DataRequirement> due = reqs.stream()
                    .filter(r -> r.getSchedule().count() > si && !(r instanceof IntervalWindowRequirement))
                    .collect(Collectors.toList());
                prefetchJcmd(due);

                for (DataRequirement req : reqs) {
                    if (req.getSchedule().count() <= sampleIndex || req instanceof IntervalWindowRequirement) {
                        continue;
                    }
                    try {
                        results.get(req).add(req.collect(helper, sampleIndex));
                    } catch (IOException e) {
                        exceptions.add(e);
                        results.get(req).add(new CollectedData(
                            System.currentTimeMillis(),
                            "",
                            Map.of("error", e.getMessage())
                        ));
                    }
                }

                if (sampleIndex < maxCount - 1) {
                    long elapsedAfterPointCollection = System.currentTimeMillis() - cycleStart;
                    long windowMs = Math.max(0, intervalMs - elapsedAfterPointCollection - BETWEEN_SAMPLE_SAFETY_MARGIN_MS);

                    for (DataRequirement req : reqs) {
                        if (!(req instanceof IntervalWindowRequirement windowRequirement)
                            || req.getSchedule().count() <= sampleIndex) {
                            continue;
                        }
                        try {
                            CollectedData sample = windowRequirement.collectWindow(helper, sampleIndex, windowMs);
                            if (!"true".equals(sample.metadata().get("skip"))) {
                                results.get(req).add(sample);
                            }
                        } catch (IOException ignored) {
                        }
                    }

                    long elapsedTotal = System.currentTimeMillis() - cycleStart;
                    long sleepMs = intervalMs - elapsedTotal;
                    if (sleepMs > 0) {
                        try {
                            Thread.sleep(sleepMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted while collecting interval data", e);
                        }
                    }
                }
            }
        }

        if (verbose && !exceptions.isEmpty()) {
            for (Exception e : exceptions) {
                System.err.println("    [jcmd] collection warning: " + e.getMessage());
            }
        }
    }
}
