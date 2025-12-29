package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.BaseAnalyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jthreaddump.model.ThreadDump;

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
        return "dead-lock";
    }

    @Override
    public Set<String> supportedOptions() {
        return Set.of("keep", "json");
    }

    @Override
    public DumpRequirement dumpRequirement() {
        return DumpRequirement.ONE;
    }

    @Override
    public AnalyzerResult analyze(List<ThreadDump> dumps, Map<String, Object> options) {
        if (dumps.isEmpty()) {
            return AnalyzerResult.ok("No thread dump available");
        }

        ThreadDump dump = dumps.get(0);
        boolean isJson = isJsonOutput(options);

        // Check if there's a deadlock section in the dump
        String deadlockInfo = extractDeadlockInfo(dump);

        if (deadlockInfo == null || deadlockInfo.isBlank()) {
            if (isJson) {
                return AnalyzerResult.ok("{\"deadlock\": false}");
            } else {
                // No deadlock - nothing to report in text mode
                return AnalyzerResult.nothing();
            }
        }

        // Deadlock found
        if (isJson) {
            String jsonOutput = String.format(
                "{\"deadlock\": true, \"details\": %s}",
                escapeJson(deadlockInfo)
            );
            return AnalyzerResult.deadlock(jsonOutput);
        } else {
            return AnalyzerResult.deadlock("Deadlock detected:\n\n" + deadlockInfo);
        }
    }

    /**
     * Extracts deadlock information from the thread dump.
     * Returns null if no deadlock is present.
     */
    private String extractDeadlockInfo(ThreadDump dump) {
        // The jthreaddump library may have deadlock info in the raw output
        // For now, we'll check if any thread has deadlock-related text
        String rawDump = dump.toString();

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

    /**
     * Escapes a string for JSON.
     */
    private String escapeJson(String text) {
        return "\"" + text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            + "\"";
    }
}