package me.bechberger.jstall.util.llm;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.analyzer.impl.*;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
import me.bechberger.jthreaddump.model.LockInfo;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.model.ThreadInfo;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Defines and executes the tools available to the AI during thread dump analysis.
 *
 * <p>These tools allow the LLM to request additional information about the application
 * being analyzed, enabling a more interactive and thorough analysis.
 */
public class AiTools {

    private final ResolvedData data;

    public AiTools(ResolvedData data) {
        this.data = data;
    }

    /**
     * Returns the list of tool definitions available for the AI to call.
     */
    public List<ToolDefinition> getToolDefinitions() {
        return List.of(
            new ToolDefinition(
                "get_thread_stack_trace",
                "Get full stack trace of a thread by name. Optionally specify dump index (1-based, default: latest).",
                List.of(
                    new ToolDefinition.Parameter("thread_name", "string",
                        "Thread name or substring"),
                    new ToolDefinition.Parameter("dump_index", "integer",
                        "Dump index 1-based (default: latest)", false)
                )
            ),
            new ToolDefinition(
                "search_stack_frames",
                "Search all thread stack traces for a class or method name.",
                List.of(
                    new ToolDefinition.Parameter("pattern", "string",
                        "Class name, method name, or package substring")
                )
            ),
            new ToolDefinition(
                "get_lock_info",
                "Get threads holding locks and threads waiting for them.",
                List.of()
            ),
            new ToolDefinition(
                "get_system_properties",
                "Get JVM system properties (java.version, classpath, VM args, etc.).",
                List.of(
                    new ToolDefinition.Parameter("filter", "string",
                        "Optional substring to filter property keys", false)
                )
            ),
            new ToolDefinition(
                "get_raw_thread_dump_section",
                "Get the raw unprocessed thread dump text for a specific thread.",
                List.of(
                    new ToolDefinition.Parameter("thread_name", "string",
                        "Thread name to find in the raw dump")
                )
            ),
            new ToolDefinition(
                "get_top_cpu_threads",
                "Get the top N threads by CPU time.",
                List.of(
                    new ToolDefinition.Parameter("count", "integer",
                        "Number of threads to return (default 10)", false)
                )
            ),
            new ToolDefinition(
                "get_dependency_tree",
                "Get thread wait chains: which threads wait on locks held by other threads.",
                List.of()
            ),
            new ToolDefinition(
                "compare_thread_across_dumps",
                "Compare a thread's state across all dumps to check if it's stuck or making progress.",
                List.of(
                    new ToolDefinition.Parameter("thread_name", "string",
                        "Thread name or substring")
                )
            ),
            new ToolDefinition(
                "get_threads_by_state",
                "Get threads in a given state with their top 3 stack frames (up to 20 threads). " +
                "States: BLOCKED, WAITING, TIMED_WAITING, RUNNABLE.",
                List.of(
                    new ToolDefinition.Parameter("state", "string",
                        "Thread state: BLOCKED, WAITING, TIMED_WAITING, or RUNNABLE")
                )
            )
        );
    }

    /**
     * Creates a ToolExecutor that can handle all defined tools.
     */
    public ToolExecutor createExecutor() {
        return call -> {
            try {
                return switch (call.name()) {
                    case "get_thread_stack_trace" -> getThreadStackTrace(call.getString("thread_name", ""), call.getInt("dump_index", -1));
                    case "search_stack_frames" -> searchStackFrames(call.getString("pattern", ""));
                    case "get_lock_info" -> getLockInfo();
                    case "get_system_properties" -> getSystemProperties(call.getString("filter", ""));
                    case "get_raw_thread_dump_section" -> getRawThreadDumpSection(call.getString("thread_name", ""));
                    case "get_top_cpu_threads" -> getTopCpuThreads(call.getInt("count", 10));
                    case "get_dependency_tree" -> getDependencyTree();
                    case "compare_thread_across_dumps" -> compareThreadAcrossDumps(call.getString("thread_name", ""));
                    case "get_threads_by_state" -> getThreadsByState(call.getString("state", "BLOCKED"));
                    default -> "Unknown tool: " + call.name();
                };
            } catch (Exception e) {
                return "Error executing tool " + call.name() + ": " + e.getMessage();
            }
        };
    }

    private ThreadDump getLatestDump() {
        List<ThreadDumpSnapshot> dumps = data.dumps();
        if (dumps.isEmpty()) return null;
        return dumps.get(dumps.size() - 1).parsed();
    }

    private String getThreadStackTrace(String threadName, int dumpIndex) {
        List<ThreadDumpSnapshot> dumps = data.dumps();
        if (dumps.isEmpty()) return "No thread dumps available.";

        ThreadDump dump;
        String dumpLabel;
        if (dumpIndex > 0 && dumpIndex <= dumps.size()) {
            dump = dumps.get(dumpIndex - 1).parsed();
            dumpLabel = "dump " + dumpIndex + "/" + dumps.size();
        } else {
            dump = dumps.get(dumps.size() - 1).parsed();
            dumpLabel = "latest dump (" + dumps.size() + "/" + dumps.size() + ")";
        }

        String lowerName = threadName.toLowerCase();
        List<ThreadInfo> matches = dump.threads().stream()
            .filter(t -> t.name().toLowerCase().contains(lowerName))
            .toList();

        if (matches.isEmpty()) {
            return "No thread found matching '" + threadName + "' in " + dumpLabel + ".";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("From ").append(dumpLabel).append(":\n\n");
        int limit = Math.min(matches.size(), 5);
        if (matches.size() > 5) {
            sb.append("Showing first 5 of ").append(matches.size()).append(" matching threads.\n\n");
        }

        for (int i = 0; i < limit; i++) {
            ThreadInfo t = matches.get(i);
            sb.append(formatThread(t));
            sb.append("\n");
        }
        return sb.toString();
    }

    private String searchStackFrames(String pattern) {
        ThreadDump dump = getLatestDump();
        if (dump == null) return "No thread dumps available.";
        if (pattern.isBlank()) return "Please provide a search pattern.";

        String lowerPattern = pattern.toLowerCase();
        List<ThreadInfo> matching = dump.threads().stream()
            .filter(t -> t.stackTrace() != null && t.stackTrace().stream()
                .anyMatch(f -> f.toString().toLowerCase().contains(lowerPattern)))
            .toList();

        if (matching.isEmpty()) {
            return "No threads have '" + pattern + "' in their stack trace.";
        }

        StringBuilder sb = new StringBuilder();
        int limit = Math.min(matching.size(), 10);
        sb.append(matching.size()).append(" thread(s) with '").append(pattern).append("' in stack");
        if (matching.size() > limit) {
            sb.append(" (showing first ").append(limit).append(" of ").append(matching.size()).append(")");
        }
        sb.append(":\n\n");
        for (int i = 0; i < limit; i++) {
            ThreadInfo t = matching.get(i);
            sb.append("## ").append(t.name()).append(" (").append(t.state()).append(")\n");
            // Show matching frames
            if (t.stackTrace() != null) {
                for (var frame : t.stackTrace()) {
                    String frameStr = frame.toString();
                    if (frameStr.toLowerCase().contains(lowerPattern)) {
                        sb.append("  → ").append(frameStr).append("\n");
                    }
                }
            }
            sb.append("\n");
        }
        if (matching.size() > 10) {
            sb.append("... and ").append(matching.size() - 10).append(" more threads\n");
        }
        return sb.toString();
    }

    private String getLockInfo() {
        ThreadDump dump = getLatestDump();
        if (dump == null) return "No thread dumps available.";

        StringBuilder sb = new StringBuilder();

        // Find threads waiting on locks (blocking)
        List<ThreadInfo> blocked = dump.threads().stream()
            .filter(t -> t.state() == Thread.State.BLOCKED)
            .toList();

        if (blocked.isEmpty()) {
            sb.append("No blocked threads found.\n");
        } else {
            sb.append("## Blocked Threads (").append(blocked.size()).append(")\n\n");
            for (ThreadInfo t : blocked) {
                sb.append("- ").append(t.name());
                t.getWaitedOnLock().ifPresent(lock ->
                    sb.append(" waiting on ").append(lock.className()).append(" (").append(lock.lockId()).append(")"));
                sb.append("\n");
            }
        }

        // Find threads holding locks
        sb.append("\n## Threads Holding Locks");
        List<ThreadInfo> holders = dump.threads().stream()
            .filter(t -> t.locks() != null && t.locks().stream().anyMatch(LockInfo::isLocked))
            .toList();
        if (!holders.isEmpty()) {
            sb.append(" (").append(holders.size()).append(" total");
            if (holders.size() > 15) sb.append(", showing first 15");
            sb.append(")");
        }
        sb.append("\n\n");

        if (holders.isEmpty()) {
            sb.append("No explicit lock holders found.\n");
        } else {
            int limit = Math.min(holders.size(), 15);
            for (int i = 0; i < limit; i++) {
                ThreadInfo t = holders.get(i);
                sb.append("- ").append(t.name()).append(" holds:\n");
                for (var lock : t.locks()) {
                    if (lock.isLocked()) {
                        sb.append("    ").append(lock.className()).append(" (").append(lock.lockId()).append(")\n");
                    }
                }
            }
            if (holders.size() > 15) {
                sb.append("... and ").append(holders.size() - 15).append(" more\n");
            }
        }
        return sb.toString();
    }

    private String getSystemProperties(String filter) {
        Map<String, String> props = data.systemProperties();
        if (props == null || props.isEmpty()) {
            return "No system properties available.";
        }

        Map<String, String> filtered;
        if (filter != null && !filter.isBlank()) {
            String lowerFilter = filter.toLowerCase();
            filtered = props.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase().contains(lowerFilter)
                    || e.getValue().toLowerCase().contains(lowerFilter))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        } else {
            filtered = props;
        }

        if (filtered.isEmpty()) {
            return "No properties matching '" + filter + "'.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(filtered.size()).append(" properties");
        if (filter != null && !filter.isBlank()) {
            sb.append(" matching '").append(filter).append("'");
        }
        sb.append(":\n\n");

        filtered.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .limit(50)
            .forEach(e -> sb.append(e.getKey()).append(" = ").append(truncate(e.getValue(), 200)).append("\n"));

        if (filtered.size() > 50) {
            sb.append("... and ").append(filtered.size() - 50).append(" more\n");
        }
        return sb.toString();
    }

    private String getRawThreadDumpSection(String threadName) {
        if (data.dumps().isEmpty()) return "No thread dumps available.";

        String raw = data.dumps().get(data.dumps().size() - 1).raw();
        String lowerName = threadName.toLowerCase();

        // Find the section in raw dump
        String[] lines = raw.split("\n");
        StringBuilder result = new StringBuilder();
        boolean capturing = false;
        int capturedLines = 0;

        for (String line : lines) {
            if (line.startsWith("\"") && line.toLowerCase().contains(lowerName)) {
                if (result.length() > 0) result.append("\n---\n\n");
                capturing = true;
                capturedLines = 0;
            }

            if (capturing) {
                result.append(line).append("\n");
                capturedLines++;
                // Stop after blank line (end of thread section) or max lines
                if ((capturedLines > 2 && line.isBlank()) || capturedLines > 100) {
                    capturing = false;
                }
            }
        }

        if (result.isEmpty()) {
            return "Thread '" + threadName + "' not found in raw dump.";
        }
        return result.toString();
    }

    private String getTopCpuThreads(int count) {
        ThreadDump dump = getLatestDump();
        if (dump == null) return "No thread dumps available.";

        count = Math.max(1, Math.min(count, 50));

        // Sort threads by CPU time (descending)
        List<ThreadInfo> sorted = dump.threads().stream()
            .filter(t -> t.cpuTimeSec() != null && t.cpuTimeSec() > 0)
            .sorted((a, b) -> Double.compare(b.cpuTimeSec(), a.cpuTimeSec()))
            .limit(count)
            .toList();

        if (sorted.isEmpty()) {
            return "No CPU time data available for threads.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Top ").append(sorted.size()).append(" threads by CPU time:\n\n");
        for (int i = 0; i < sorted.size(); i++) {
            ThreadInfo t = sorted.get(i);
            sb.append(i + 1).append(". ").append(t.name())
              .append(" — ").append(String.format("%.3fs CPU", t.cpuTimeSec()))
              .append(" (").append(t.state()).append(")");
            if (t.stackTrace() != null && !t.stackTrace().isEmpty()) {
                sb.append("\n   top: ").append(t.stackTrace().get(0));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String getDependencyTree() {
        AnalyzerResult result = new DependencyTreeAnalyzer().analyze(data, Map.of());
        if (result.output().isBlank()) {
            return "No thread dependencies found (no lock-based waiting detected).";
        }
        String output = result.output();
        if (output.length() > 5000) {
            output = output.substring(0, 5000) + "\n... (truncated)";
        }
        return output;
    }

    private String compareThreadAcrossDumps(String threadName) {
        List<ThreadDumpSnapshot> dumps = data.dumps();
        if (dumps.isEmpty()) return "No thread dumps available.";
        if (dumps.size() < 2) return "Only 1 dump available — need at least 2 to compare.";

        String lowerName = threadName.toLowerCase();
        StringBuilder sb = new StringBuilder();
        sb.append("Thread '").append(threadName).append("' across ").append(dumps.size()).append(" dumps:\n\n");

        boolean found = false;
        for (int i = 0; i < dumps.size(); i++) {
            ThreadDump dump = dumps.get(i).parsed();
            List<ThreadInfo> matches = dump.threads().stream()
                .filter(t -> t.name().toLowerCase().contains(lowerName))
                .toList();

            if (matches.isEmpty()) continue;
            found = true;

            // Show first match (most likely the thread of interest)
            ThreadInfo t = matches.get(0);
            sb.append("## Dump ").append(i + 1);
            if (dump.timestamp() != null) {
                sb.append(" (").append(dump.timestamp()).append(")");
            }
            sb.append("\n");
            sb.append("  State: ").append(t.state());
            if (t.cpuTimeSec() != null) {
                sb.append(" | CPU: ").append(String.format("%.3fs", t.cpuTimeSec()));
            }
            sb.append("\n");
            if (t.stackTrace() != null && !t.stackTrace().isEmpty()) {
                int limit = Math.min(t.stackTrace().size(), 5);
                for (int j = 0; j < limit; j++) {
                    sb.append("    at ").append(t.stackTrace().get(j)).append("\n");
                }
                if (t.stackTrace().size() > 5) {
                    sb.append("    ... ").append(t.stackTrace().size() - 5).append(" more\n");
                }
            }
            sb.append("\n");
        }

        if (!found) {
            return "Thread '" + threadName + "' not found in any dump.";
        }
        return sb.toString();
    }

    // --- Utility methods ---

    public String getThreadsByState(String stateStr) {
        ThreadDump dump = getLatestDump();
        if (dump == null) return "No thread dumps available.";

        Thread.State targetState;
        try {
            targetState = Thread.State.valueOf(stateStr.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return "Unknown thread state: '" + stateStr
                + "'. Valid: BLOCKED, WAITING, TIMED_WAITING, RUNNABLE, NEW, TERMINATED.";
        }

        List<ThreadInfo> matching = dump.threads().stream()
            .filter(t -> t.state() == targetState)
            .toList();

        if (matching.isEmpty()) return "No threads in state " + targetState + ".";

        int limit = Math.min(matching.size(), 20);
        StringBuilder sb = new StringBuilder();
        sb.append(matching.size()).append(" thread(s) in state ").append(targetState);
        if (matching.size() > limit) sb.append(" (showing first ").append(limit).append(")");
        sb.append(":\n\n");

        for (int i = 0; i < limit; i++) {
            ThreadInfo t = matching.get(i);
            sb.append("\"").append(t.name()).append("\"");
            t.getWaitedOnLock().ifPresent(lock ->
                sb.append(" [waiting on ").append(lock.className())
                  .append(" ").append(lock.lockId()).append("]"));
            sb.append("\n");
            if (t.stackTrace() != null) {
                int frames = Math.min(t.stackTrace().size(), 3);
                for (int j = 0; j < frames; j++) {
                    sb.append("    at ").append(t.stackTrace().get(j)).append("\n");
                }
                if (t.stackTrace().size() > 3)
                    sb.append("    ... ").append(t.stackTrace().size() - 3).append(" more frames\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String formatThread(ThreadInfo t) {
        StringBuilder sb = new StringBuilder();
        sb.append("\"").append(t.name()).append("\"");
        sb.append(" - state: ").append(t.state());
        t.getWaitedOnLock().ifPresent(lock ->
            sb.append(" (waiting on ").append(lock.className()).append(" ").append(lock.lockId()).append(")"));
        sb.append("\n");
        if (t.stackTrace() != null) {
            int limit = Math.min(t.stackTrace().size(), 30);
            for (int i = 0; i < limit; i++) {
                sb.append("    at ").append(t.stackTrace().get(i)).append("\n");
            }
            if (t.stackTrace().size() > 30) {
                sb.append("    ... ").append(t.stackTrace().size() - 30).append(" more\n");
            }
        }
        return sb.toString();
    }

    private static String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
