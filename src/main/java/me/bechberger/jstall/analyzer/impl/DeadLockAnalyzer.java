package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.BaseAnalyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.model.ThreadDumpSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects JVM-reported thread deadlocks.

 * Uses only the first thread dump.
 * Returns exit code 2 if deadlock is detected.
 */
public class DeadLockAnalyzer extends BaseAnalyzer {

    @Override
    public String name() {
        return "deadlock";
    }

    @Override
    public Set<String> supportedOptions() {
        return Set.of("keep");
    }

    @Override
    public DumpRequirement dumpRequirement() {
        return DumpRequirement.ONE;
    }

    @Override
    public AnalyzerResult analyze(List<ThreadDumpSnapshot> dumpsWithRaw, Map<String, Object> options) {
        if (dumpsWithRaw.isEmpty()) {
            return AnalyzerResult.ok("No thread dump available");
        }

        ThreadDumpSnapshot dumpWithRaw = dumpsWithRaw.getFirst();

        // Check if there's a deadlock section in the raw dump
        String deadlockInfo = extractDeadlockInfo(dumpWithRaw.raw());

        if (deadlockInfo == null || deadlockInfo.isBlank()) {
            // No deadlock - nothing to report in text mode
            return AnalyzerResult.nothing();
        }
        return AnalyzerResult.deadlock("Deadlock detected:\n\n" + deadlockInfo);
    }

    /**
     * Extracts deadlock information from the raw thread dump string.
     * Returns null if no deadlock is present.
     */
    private String extractDeadlockInfo(String rawDump) {
        // Look for standard JVM deadlock reporting
        int deadlockIndex = rawDump.indexOf("Found one Java-level deadlock:");
        if (deadlockIndex == -1) {
            deadlockIndex = rawDump.indexOf("Found Java-level deadlock:");
        }

        if (deadlockIndex != -1) {
            // Extract from deadlock marker to end or next major section
            int endIndex = rawDump.indexOf("\n\n\n", deadlockIndex);
            if (endIndex == -1) {
                endIndex = rawDump.length();
            }
            return rawDump.substring(deadlockIndex, endIndex).trim();
        }

        return null;
    }
}