package me.bechberger.jstall.util.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compresses the verbose jstall status output before sending to a local LLM.
 *
 * <p>Removes or summarizes sections that are high-token-cost but carry low signal
 * for small models (classloader stats tables, verbose metaspace tables, repeated
 * per-sample compiler-queue rows, duplicate GC metric unit columns).
 */
public class ContextCompressor {

    // Known top-level section names — used to distinguish top-level from nested headers
    private static final java.util.Set<String> NESTED_HEADERS = java.util.Set.of(
        "Usage", "Virtual Space", "Summary"
    );

    private static final Pattern SECTION_HEADER = Pattern.compile("^=== (.+?) ===$", Pattern.MULTILINE);

    /**
     * Compress the status analyzer output to reduce token count.
     * Keeps all semantically meaningful content; strips verbose formatting noise.
     *
     * @param input raw status analyzer output
     * @return compressed output, same section ordering
     */
    public static String compress(String input) {
        if (input == null || input.isBlank()) return input;

        Map<String, String> sections = splitSections(input);

        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : sections.entrySet()) {
            String name = entry.getKey();
            String body = entry.getValue();
            String compressed = compressSection(name, body);
            if (compressed != null && !compressed.isBlank()) {
                out.append("=== ").append(name).append(" ===\n");
                out.append(compressed.stripTrailing()).append("\n\n");
            }
        }

        // Append a thread-count warning when total threads is abnormally high.
        // Extract total count from the "threads" section state distribution line.
        String threadsBody = sections.get("threads");
        int totalThreads = extractTotalThreadCount(threadsBody);
        if (totalThreads > 100) {
            out.append("=== thread-count-warning ===\n");
            out.append("WARNING: ").append(totalThreads).append(" threads detected.");
            if (totalThreads > 500) {
                out.append(" This is extremely high — likely a thread leak.");
            } else if (totalThreads > 200) {
                out.append(" This is very high — possible thread leak or oversized thread pool.");
            } else {
                out.append(" This is high — consider whether thread pool is oversized.");
            }
            out.append("\n\n");
        }

        // When WAITING threads exist but are hidden from the table (table only shows top-N by CPU),
        // inject a note so the model knows to investigate without calling a tool.
        // Use >= 2 hidden threshold to avoid false positives from 1 JVM housekeeping thread (Finalizer).
        // This catches: rl-waiter threads (8 hidden), Semaphore waiters, BlockingQueue producers/consumers.
        int waitingCount = extractStateCount(threadsBody, "WAITING");
        int waitingInTable = countStateInTable(threadsBody, "WAITING");
        int hiddenWaiting = waitingCount - waitingInTable;
        if (hiddenWaiting >= 2) {
            out.append("=== hidden-waiting-threads ===\n");
            out.append("INVESTIGATION REQUIRED: ").append(waitingCount)
               .append(" WAITING thread(s) detected — none visible in table because they have 0 CPU.\n");
            out.append("0 CPU% for WAITING threads is NORMAL for threads blocked on a ReentrantLock or Semaphore — they do not spin.\n");
            out.append("The absence of lock-based dependency trees is EXPECTED for AQS-based locks (ReentrantLock, Semaphore) — they use LockSupport.park, not Java monitors.\n");
            out.append("Call get_threads_by_state WAITING NOW to determine if these threads are stuck on a lock.\n\n");
        }

        // When many TIMED_WAITING threads exist but are hidden from the table, this can indicate
        // pool starvation — workers asleep/blocked on a queue while tasks pile up. Many JVMs have
        // a few legitimate TIMED_WAITING threads (Reference Handler, etc.), so use a higher threshold.
        int timedWaitingCount = extractStateCount(threadsBody, "TIMED_WAITING");
        int timedWaitingInTable = countStateInTable(threadsBody, "TIMED_WAITING");
        int hiddenTimedWaiting = timedWaitingCount - timedWaitingInTable;
        if (hiddenTimedWaiting >= 4) {
            out.append("=== hidden-timed-waiting-threads ===\n");
            out.append("INVESTIGATION REQUIRED: ").append(timedWaitingCount)
               .append(" TIMED_WAITING thread(s) detected (")
               .append(hiddenTimedWaiting).append(" hidden from table because they have 0 CPU).\n");
            out.append("This may indicate pool starvation (workers asleep while tasks pile up) OR may be benign idle threads (Thread.sleep loops, scheduled executors).\n");
            out.append("Call get_threads_by_state TIMED_WAITING NOW to inspect names and stacks. If names contain 'pool', 'worker', or 'executor', flag as pool starvation. If names match generic sleeper patterns (e.g. 'sleeper-N'), flag as idle/healthy.\n\n");
        }

        // When many RUNNABLE threads exist with effectively 0 CPU, they are likely blocked in
        // native code (e.g. socketRead0, file I/O, JNI). Java reports these as RUNNABLE because
        // the JVM can't observe native-code blocking. This is a frequent source of "stuck process
        // looks healthy" misdiagnosis.
        // Only fire if total CPU is very low AND many RUNNABLE are hidden from the table.
        int runnableCount = extractStateCount(threadsBody, "RUNNABLE");
        int runnableInTable = countStateInTable(threadsBody, "RUNNABLE");
        int hiddenRunnable = runnableCount - runnableInTable;
        double totalCpuPct = extractTotalCpuPercent(threadsBody);
        if (hiddenRunnable >= 4 && totalCpuPct >= 0 && totalCpuPct < 5.0) {
            out.append("=== hidden-runnable-threads ===\n");
            out.append("INVESTIGATION REQUIRED: ").append(runnableCount)
               .append(" RUNNABLE thread(s) detected (").append(hiddenRunnable)
               .append(" hidden from table because they have 0 CPU) but total CPU is only ")
               .append(String.format("%.1f", totalCpuPct)).append("%.\n");
            out.append("RUNNABLE with 0 CPU usually means the thread is blocked in native code — typical causes: socket/file I/O (socketRead0, FileInputStream.readBytes), JNI calls, native locks.\n");
            out.append("Call get_threads_by_state RUNNABLE NOW to inspect stacks. If stacks contain socketRead0, readBytes, or native methods, flag as blocked I/O / native wait.\n\n");
        }

        return out.toString().stripTrailing();
    }

    /** Sum all per-state counts from the "Thread state distribution:" line. */
    private static int extractTotalThreadCount(String threadsBody) {
        if (threadsBody == null) return 0;
        for (String line : threadsBody.split("\n")) {
            if (line.startsWith("Thread state distribution:")) {
                int total = 0;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s+[A-Z_]+").matcher(line);
                while (m.find()) total += Integer.parseInt(m.group(1));
                return total;
            }
        }
        return 0;
    }

    /** Extract the count for a specific state from the distribution line. */
    private static int extractStateCount(String threadsBody, String state) {
        if (threadsBody == null) return 0;
        for (String line : threadsBody.split("\n")) {
            if (line.startsWith("Thread state distribution:")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s+" + state + "\\b").matcher(line);
                if (m.find()) return Integer.parseInt(m.group(1));
            }
        }
        return 0;
    }

    /** Extract the total CPU percent from the "Combined CPU time" line. Returns -1 if not found. */
    private static double extractTotalCpuPercent(String threadsBody) {
        if (threadsBody == null) return -1;
        Pattern p = Pattern.compile("\\(([0-9]+(?:\\.[0-9]+)?)%\\s*total CPU");
        for (String line : threadsBody.split("\n")) {
            Matcher m = p.matcher(line);
            if (m.find()) return Double.parseDouble(m.group(1));
        }
        return -1;
    }

    /** Count how many table rows have the exact WAITING state (not TIMED_WAITING). */
    private static int countStateInTable(String threadsBody, String state) {
        if (threadsBody == null) return 0;
        int count = 0;
        boolean inTable = false;
        // Match the state word but not as a suffix of another state (e.g. WAITING but not TIMED_WAITING)
        Pattern p = Pattern.compile("(?<![A-Z_])" + Pattern.quote(state) + "(?![A-Z_])");
        for (String line : threadsBody.split("\n")) {
            if (line.startsWith("THREAD")) { inTable = true; continue; }
            if (line.startsWith("-")) continue;
            if (!inTable || line.isBlank()) continue;
            if (p.matcher(line).find()) count++;
        }
        return count;
    }

    /**
     * Split the input into ordered top-level sections by their === name === headers.
     * Nested headers (Usage, Virtual Space, Summary inside vm-metaspace) are treated
     * as body content of their enclosing section.
     */
    private static Map<String, String> splitSections(String input) {
        Map<String, String> sections = new LinkedHashMap<>();
        Matcher m = SECTION_HEADER.matcher(input);

        List<String> names = new ArrayList<>();
        List<Integer> headerStarts = new ArrayList<>();  // start of "=== name ===" line
        List<Integer> bodyStarts = new ArrayList<>();    // start of body (after header newline)

        while (m.find()) {
            String name = m.group(1).trim();
            if (!NESTED_HEADERS.contains(name)) {
                names.add(name);
                headerStarts.add(m.start());
                bodyStarts.add(m.end());
            }
            // Nested headers stay as body content of their parent section
        }

        for (int i = 0; i < names.size(); i++) {
            int bodyStart = bodyStarts.get(i);
            if (bodyStart < input.length() && input.charAt(bodyStart) == '\n') bodyStart++;
            int bodyEnd = (i + 1 < headerStarts.size()) ? headerStarts.get(i + 1) : input.length();
            String body = input.substring(bodyStart, bodyEnd).stripTrailing();
            sections.put(names.get(i), body);
        }
        return sections;
    }

    private static String compressSection(String name, String body) {
        return switch (name) {
            case "uptime"               -> null;   // noise — not useful for diagnosis
            case "vm-classloader-stats" -> compressClassloaderStats(body);
            case "vm-metaspace"         -> compressMetaspace(body);
            case "gc-heap-info"         -> compressGcHeapInfo(body);
            case "compiler-queue"       -> compressCompilerQueue(body);
            case "vm-vitals"            -> skipIfUnavailable(body);
            case "most-work"            -> compressMostWork(body);
            case "threads"              -> compressThreadsTable(body);
            case "dependency-tree"      -> compressDependencyTree(body);
            case "deadlock"             -> compressDeadlock(body);
            default                     -> body;
        };
    }

    // ── vm-classloader-stats ───────────────────────────────────────────────────

    /**
     * Keep only the totals row and a one-line summary of loader count.
     * Original ~10 rows × 5 columns → 2 lines.
     */
    private static String compressClassloaderStats(String body) {
        String[] lines = body.split("\n");
        String sampleLine = null;
        int loaderCount = 0;
        int totalClasses = 0;

        for (String line : lines) {
            if (line.startsWith("VM.classloader_stats")) sampleLine = line;
            if (line.startsWith("Total")) {
                String[] parts = line.trim().split("\\s{2,}");
                if (parts.length >= 2) {
                    try {
                        totalClasses = Integer.parseInt(parts[1].replace(",", "").trim());
                    } catch (NumberFormatException ignored) {}
                }
            }
            if (!line.isBlank() && !line.startsWith("-") && !line.startsWith("Type")
                    && !line.startsWith("Total") && !line.startsWith("VM.")) {
                loaderCount++;
            }
        }

        // Suppress entirely when classloader count is modest and no active loading
        if (loaderCount <= 5) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("Total classes: ").append(totalClasses > 0 ? totalClasses : "?");
        sb.append(" across ").append(loaderCount).append(" loaders\n");
        return sb.toString();
    }

    // ── vm-metaspace ──────────────────────────────────────────────────────────

    /**
     * Keep only the "Both" summary row from the Usage table plus max/threshold from Summary.
     * Emits a compact one-liner: "Metaspace: 96MB used / 98MB committed (max 384MB)"
     * to prevent the LLM from confusing committed with the actual limit.
     */
    private static String compressMetaspace(String body) {
        String[] lines = body.split("\n");
        String sampleLine = null;
        String bothRow = null;
        String maxLine = null;
        String section = "";

        for (String line : lines) {
            if (line.equals("=== Usage ==="))         { section = "usage"; continue; }
            if (line.equals("=== Virtual Space ==="))  { section = "vspace"; continue; }
            if (line.equals("=== Summary ==="))        { section = "summary"; continue; }

            switch (section) {
                case "usage" -> {
                    if (line.startsWith("VM.")) sampleLine = line;
                    else if (line.startsWith("Both") && !line.isBlank()) bothRow = line;
                }
                case "summary" -> {
                    if (!line.isBlank() && line.contains("MaxMetaspaceSize")) maxLine = line;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        if (sampleLine != null) sb.append(sampleLine).append("\n");
        if (bothRow != null) {
            sb.append(bothRow);
            // Append MaxMetaspaceSize inline so the model knows the actual limit
            if (maxLine != null) {
                String max = extractFirstValue(maxLine, "MaxMetaspaceSize:", "CompressedClassSpaceSize:");
                if (max != null) sb.append("  (max ").append(max).append(")");
            }
            sb.append("\n");
        }
        // Drop the verbose MaxMetaspaceSize line — we already inlined the value above
        return sb.toString().isBlank() ? body : sb.toString();
    }

    /** Extract the value after the first matching key token (stops at the next key or end of string). */
    private static String extractFirstValue(String line, String... keys) {
        for (String key : keys) {
            int idx = line.indexOf(key);
            if (idx >= 0) {
                String rest = line.substring(idx + key.length()).trim();
                // value is the next whitespace-delimited token
                int end = rest.indexOf(' ');
                return end >= 0 ? rest.substring(0, end) : rest;
            }
        }
        return null;
    }

    // ── gc-heap-info ──────────────────────────────────────────────────────────

    /**
     * Keep key rows (heap used%, heap total, young regions, metaspace used, survivor).
     * Strip the redundant "Details" MiB column; keep the Δ column.
     */
    private static String compressGcHeapInfo(String body) {
        String[] lines = body.split("\n");
        StringBuilder sb = new StringBuilder();
        boolean inTable = false;
        // Only keep heap used% (most important) and young regions (GC pressure signal)
        String[] keepPrefixes = {"Heap used", "Young regions"};

        for (String line : lines) {
            if (line.startsWith("GC.heap_info")) { sb.append(line).append("\n"); inTable = true; continue; }
            if (line.startsWith("Metric") || line.startsWith("-")) { /* skip header/separator */ continue; }
            if (!inTable) { sb.append(line).append("\n"); continue; }
            if (line.isBlank()) { sb.append("\n"); inTable = false; continue; }

            boolean keep = false;
            for (String prefix : keepPrefixes) {
                if (line.trim().startsWith(prefix)) { keep = true; break; }
            }
            if (keep) {
                sb.append(stripDetailsColumn(line)).append("\n");
            }
        }
        return sb.toString().isBlank() ? body : sb.toString();
    }

    /**
     * Remove the middle "Details" column from a GC heap info row.
     * Columns separated by 2+ spaces: MetricName, Value, [Details MiB], Δvalue
     * We want: MetricName  Value  Δvalue
     */
    private static String stripDetailsColumn(String line) {
        String[] parts = line.split("  +");
        if (parts.length < 3) return line;
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            // Drop columns that are purely a size like "330.4 MiB" (Details column)
            if (p.matches("\\d+\\.\\d+ [MG]iB.*") && !p.contains("Δ")) continue;
            if (out.length() > 0) out.append("  ");
            out.append(p);
        }
        return out.toString();
    }

    // ── most-work ─────────────────────────────────────────────────────────────

    public static final int MAX_STACK_FRAMES = 4;

    // JVM-internal thread names that are noise in the most-work section
    private static final java.util.Set<String> JVM_INTERNAL_THREADS = java.util.Set.of(
        "C1 CompilerThread0", "C2 CompilerThread0", "C2 CompilerThread1",
        "JVMCI-native CompilerThread0", "JVMCI CompilerThread0",
        "Monitor Deflation Thread", "Service Thread", "Notification Thread",
        "Reference Handler", "Finalizer", "Signal Dispatcher",
        "Common-Cleaner", "Attach Listener", "G1 Main Marker",
        "G1 Refine#0", "G1 Service", "G1 Conc#0",
        "VM Thread", "VM Periodic Task Thread",
        "RMI TCP Accept-0", "RMI Scheduler(0)", "RMI TCP Connection(idle)"
    );

    /**
     * Strip JVM-internal threads from the most-work listing and truncate stacks.
     * Keeps only application threads (top 5 max) plus the summary line.
     */
    private static String compressMostWork(String body) {
        String[] lines = body.split("\n");
        StringBuilder sb = new StringBuilder();
        int stackFrameCount = 0;
        boolean inStack = false;
        boolean inJvmThread = false;
        int appThreadCount = 0;

        for (String line : lines) {
            boolean isNumberedEntry = line.matches("^\\d+\\. .+");
            if (isNumberedEntry) {
                String threadName = line.replaceFirst("^\\d+\\. ", "").trim();
                inJvmThread = JVM_INTERNAL_THREADS.contains(threadName);
                if (!inJvmThread) appThreadCount++;
                inStack = false;
                stackFrameCount = 0;
            }

            if (inJvmThread) continue;

            boolean isStackLine = line.startsWith("     at ") || line.startsWith("     ...");
            if (isStackLine) {
                if (!inStack) { inStack = true; stackFrameCount = 0; }
                if (stackFrameCount < MAX_STACK_FRAMES) {
                    sb.append(line).append("\n");
                    if (!line.contains("omitted")) stackFrameCount++;
                }
            } else {
                inStack = false;
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    // ── threads ───────────────────────────────────────────────────────────────

    /**
     * The threads table often duplicates most-work. Collapse it to just:
     * - The summary line (state distribution + total CPU)
     * - Rows for app threads only (drop pure JVM-internal rows)
     * - Drop the header/separator rows to save tokens
     */
    private static String compressThreadsTable(String body) {
        if (body == null || body.isBlank()) return body;
        String[] lines = body.split("\n");
        StringBuilder sb = new StringBuilder();
        boolean inTable = false;

        for (String line : lines) {
            if (line.startsWith("THREAD")) { inTable = true; continue; } // drop header
            if (line.startsWith("---"))    { continue; }                  // drop separator
            if (!inTable) {
                sb.append(line).append("\n");
                continue;
            }
            if (line.isBlank()) { inTable = false; sb.append("\n"); continue; }

            // Check if this row is a pure JVM-internal thread
            boolean isJvmRow = false;
            for (String jvm : JVM_INTERNAL_THREADS) {
                if (line.startsWith(jvm)) { isJvmRow = true; break; }
            }
            if (!isJvmRow) sb.append(line).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    // ── compiler-queue ────────────────────────────────────────────────────────
    /**
     * When all samples show 0 active/queued tasks, collapse to a single summary line.
     * Keep the per-sample table when there's actual JIT activity.
     */
    private static String compressCompilerQueue(String body) {
        // Always keep just the header + Summary block; strip Per-sample breakdown and Latest snapshot.
        // For idle queues, suppress the section entirely (caller handles null → skip section).
        int summaryStart = body.indexOf("Summary:");
        if (summaryStart < 0) return body;
        int summaryEnd = body.indexOf("\n\n", summaryStart);
        String summary = summaryEnd >= 0
            ? body.substring(summaryStart, summaryEnd)
            : body.substring(summaryStart);

        // If fully idle, drop the section
        if (summary.contains("Active compilations: 0") && summary.contains("Queued tasks: 0")) {
            return null;
        }

        // Otherwise keep header + summary only
        String firstLine = body.split("\n")[0];
        return firstLine + "\n" + summary;
    }

    // ── deadlock ──────────────────────────────────────────────────────────────

    /**
     * Strip the verbose "Java stack information" block from the deadlock section.
     * Keeps the "waiting to lock ... which is held by ..." summary lines — these
     * are sufficient for diagnosis. The full stacks are in most-work anyway.
     */
    private static String compressDeadlock(String body) {
        if (body == null || body.isBlank()) return body;
        // Drop everything from "Java stack information" onward, then re-add found count
        int stackInfoIdx = body.indexOf("Java stack information");
        if (stackInfoIdx < 0) return body;

        String summary = body.substring(0, stackInfoIdx).stripTrailing();
        // Append "Found N deadlock(s)" from the tail
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("Found \\d+ deadlock[^\n]*").matcher(body);
        String found = m.find() ? "\n" + m.group() : "";
        return summary + found;
    }

    // ── dependency-tree ───────────────────────────────────────────────────────

    /**
     * Strip the verbose Java stack trace from the deadlock section (already shown in most-work)
     * and truncate each bottleneck tree to MAX_BLOCKED_SHOWN blocked threads.
     * Also collapses the "no lock-based dependency trees" no-op body to a single line.
     */
    private static final int MAX_BLOCKED_SHOWN = 6;

    private static String compressDependencyTree(String body) {
        if (body == null || body.isBlank()) return body;

        // Drop the entire section when it carries no signal
        if (body.contains("No lock-based dependency trees")) return null;

        // Drop the verbose JVM jcmd Java stack trace block (between "===...===" lines).
        String stripped = body
            .replaceAll("(?s)===={3,}.*?===={3,}\n?", "")
            .replaceAll("(?m)^\t.*\n", "")
            .replaceAll("(?m)^(Found \\d+ deadlock[^\n]*)\n", "")
            .replaceAll("\n{3,}", "\n\n")
            .stripTrailing();

        // Collapse long blocked-thread lists per bottleneck: keep first MAX_BLOCKED_SHOWN
        String[] lines = stripped.split("\n");
        StringBuilder sb = new StringBuilder();
        int blockedCount = 0;
        boolean inBlockedList = false;

        for (String line : lines) {
            boolean isBlockedEntry = line.matches("^  \\[.*?\\] .+");
            if (isBlockedEntry) {
                if (!inBlockedList) { inBlockedList = true; blockedCount = 0; }
                blockedCount++;
                if (blockedCount <= MAX_BLOCKED_SHOWN) {
                    sb.append(line).append("\n");
                } else if (blockedCount == MAX_BLOCKED_SHOWN + 1) {
                    // placeholder filled in below when we know total
                }
            } else {
                if (inBlockedList && blockedCount > MAX_BLOCKED_SHOWN) {
                    sb.append("  ... ").append(blockedCount - MAX_BLOCKED_SHOWN)
                      .append(" more blocked thread(s) omitted\n");
                }
                inBlockedList = false;
                blockedCount = 0;
                sb.append(line).append("\n");
            }
        }
        if (inBlockedList && blockedCount > MAX_BLOCKED_SHOWN) {
            sb.append("  ... ").append(blockedCount - MAX_BLOCKED_SHOWN)
              .append(" more blocked thread(s) omitted\n");
        }

        String result = sb.toString().stripTrailing();
        return result.isBlank() ? body : result;
    }

    // ── vm-vitals ─────────────────────────────────────────────────────────────

    /** Drop the section entirely when the tool is unavailable. */
    private static String skipIfUnavailable(String body) {
        if (body.contains("not available")) return null;
        return body;
    }
}
