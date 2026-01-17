package me.bechberger.jstall.analyzer;

import me.bechberger.jthreaddump.model.StackFrame;
import me.bechberger.jthreaddump.model.ThreadInfo;

import java.util.*;
import java.util.function.Predicate;

/**
 * Categorizes thread activity based on stack traces.
 *
 * Analyzes the top stack frames to determine what kind of work a thread is doing,
 * such as I/O operations, socket operations, computation, etc.
 */
public class ThreadActivityCategorizer {

    /**
     * Categories of thread activity
     */
    public enum Category {
        NETWORK_READ("Network Read"),
        NETWORK_WRITE("Network Write"),
        NETWORK("Network"),
        IO_READ("I/O Read"),
        IO_WRITE("I/O Write"),
        IO("I/O"),
        DB("Database"),
        EXTERNAL_PROCESS("External Process"),
        LOCK_WAIT("Lock Wait"),
        SLEEP("Sleep"),
        PARK("Park"),
        COMPUTATION("Computation"),
        UNKNOWN("Unknown");

        private final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Rules for categorizing threads based on stack frames
     */
    private static final List<CategoryRule> RULES = new ArrayList<>();

    static {
        // External Process operations (most specific, check first)
        addRule(Category.EXTERNAL_PROCESS, frame ->
            frame.className().equals("java.lang.ProcessHandleImpl") ||
            frame.className().equals("java.lang.ProcessImpl") ||
            frame.className().startsWith("java.lang.Process") ||
            (frame.className().contains("Process") &&
             (frame.methodName().contains("wait") || frame.methodName().contains("reaper")))
        );

        // Database operations
        addRule(Category.DB, frame ->
            frame.className().startsWith("java.sql.") ||
            frame.className().startsWith("javax.sql.") ||
            frame.className().contains("jdbc") ||
            frame.className().contains("JDBC") ||
            frame.className().contains(".sql.") ||
            frame.className().contains("ResultSet") && !frame.className().contains("nio")
        );

        // Network Read operations (sockets, selectors reading)
        addRule(Category.NETWORK_READ, frame ->
            // Socket reads
            (frame.className().contains("Socket") &&
             (frame.methodName().contains("read") || frame.methodName().contains("Read") ||
              frame.methodName().equals("accept"))) ||
            frame.className().equals("sun.nio.ch.SocketChannelImpl") &&
             (frame.methodName().equals("read") || frame.methodName().contains("Read")) ||
            frame.className().equals("java.net.SocketInputStream") && frame.methodName().contains("read") ||
            frame.className().contains("ServerSocket") && frame.methodName().equals("accept") ||
            // Unix domain sockets
            frame.className().equals("sun.nio.ch.UnixDomainSockets") &&
             (frame.methodName().contains("accept") || frame.methodName().contains("read")) ||
            // Netty reads
            (frame.className().startsWith("io.netty.channel") && frame.methodName().contains("read"))
        );

        // Network Write operations (sockets writing)
        addRule(Category.NETWORK_WRITE, frame ->
            (frame.className().contains("Socket") &&
             (frame.methodName().contains("write") || frame.methodName().contains("Write"))) ||
            frame.className().equals("sun.nio.ch.SocketChannelImpl") &&
             (frame.methodName().equals("write") || frame.methodName().contains("Write")) ||
            frame.className().equals("java.net.SocketOutputStream") && frame.methodName().contains("write") ||
            // Netty writes
            (frame.className().startsWith("io.netty.channel") && frame.methodName().contains("write"))
        );

        // Network operations (selectors, polling, general networking)
        addRule(Category.NETWORK, frame ->
            // NIO Selectors (KQueue, EPoll, etc.)
            (frame.className().contains("Selector") &&
             (frame.methodName().contains("select") || frame.methodName().contains("poll"))) ||
            frame.className().equals("sun.nio.ch.KQueue") && frame.methodName().equals("poll") ||
            frame.className().equals("sun.nio.ch.EPoll") && frame.methodName().contains("wait") ||
            frame.className().equals("sun.nio.ch.WindowsSelectorImpl") && frame.methodName().contains("doSelect") ||
            frame.className().contains("KQueueSelectorImpl") && frame.methodName().contains("doSelect") ||
            frame.className().contains("EPollSelectorImpl") && frame.methodName().contains("doSelect") ||
            // Netty (general, not read/write specific)
            frame.className().startsWith("io.netty.channel.nio") && !frame.methodName().contains("read") && !frame.methodName().contains("write") ||
            frame.className().startsWith("io.netty.channel.epoll") ||
            frame.className().startsWith("io.netty.channel.kqueue") ||
            // Watch services (file system monitoring, similar to network I/O)
            (frame.className().contains("WatchService") &&
             (frame.methodName().equals("take") || frame.methodName().equals("poll")))
        );

        // I/O Read operations (file reads)
        addRule(Category.IO_READ, frame ->
            (frame.className().startsWith("java.io") &&
             (frame.methodName().contains("read") || frame.methodName().contains("Read"))) ||
            (frame.className().contains("InputStream") &&
             !frame.className().contains("Socket") && frame.methodName().contains("read")) ||
            (frame.className().contains("Reader") && frame.methodName().contains("read")) ||
            frame.className().equals("sun.nio.ch.FileChannelImpl") && frame.methodName().equals("read") ||
            frame.className().equals("java.nio.channels.FileChannel") && frame.methodName().contains("read")
        );

        // I/O Write operations (file writes)
        addRule(Category.IO_WRITE, frame ->
            (frame.className().startsWith("java.io") &&
             (frame.methodName().contains("write") || frame.methodName().contains("Write") ||
              frame.methodName().contains("flush") || frame.methodName().contains("Flush"))) ||
            (frame.className().contains("OutputStream") &&
             !frame.className().contains("Socket") &&
             (frame.methodName().contains("write") || frame.methodName().contains("flush"))) ||
            (frame.className().contains("Writer") &&
             (frame.methodName().contains("write") || frame.methodName().contains("flush"))) ||
            frame.className().equals("sun.nio.ch.FileChannelImpl") && frame.methodName().equals("write") ||
            frame.className().equals("java.nio.channels.FileChannel") && frame.methodName().contains("write")
        );

        // Generic I/O operations (not read/write specific)
        addRule(Category.IO, frame ->
            frame.className().startsWith("java.io") ||
            frame.className().startsWith("java.nio.file") ||
            (frame.className().contains("Channel") &&
             !frame.className().contains("Socket") &&
             !frame.className().contains("netty"))
        );

        // Lock Wait - threads waiting for locks
        addRule(Category.LOCK_WAIT, frame ->
            frame.className().equals("jdk.internal.misc.Unsafe") &&
            (frame.methodName().equals("park") || frame.methodName().contains("wait")) ||
            frame.className().equals("sun.misc.Unsafe") &&
            (frame.methodName().equals("park") || frame.methodName().contains("wait")) ||
            frame.className().equals("java.util.concurrent.locks.LockSupport") &&
            frame.methodName().equals("park") ||
            frame.className().equals("java.lang.Object") && frame.methodName().equals("wait")
        );

        // Sleep
        addRule(Category.SLEEP, frame ->
            frame.className().equals("java.lang.Thread") &&
            (frame.methodName().equals("sleep") || frame.methodName().equals("yield"))
        );

        // Park (similar to lock wait but more specific)
        addRule(Category.PARK, frame ->
            frame.className().equals("java.util.concurrent.locks.LockSupport") &&
            frame.methodName().startsWith("park")
        );
    }

    /**
     * Adds a categorization rule
     */
    private static void addRule(Category category, Predicate<StackFrame> predicate) {
        RULES.add(new CategoryRule(category, predicate));
    }

    /**
     * Categorizes a thread based on its stack trace.
     *
     * Examines the top frames (up to depth 5) to determine the thread's activity.
     * Returns the most specific category found - if top frame is uncategorized but
     * a frame below it is categorized, the categorized one is returned.
     *
     * @param thread The thread to categorize
     * @return The category of activity
     */
    public static Category categorize(ThreadInfo thread) {
        if (thread.stackTrace() == null || thread.stackTrace().isEmpty()) {
            return Category.UNKNOWN;
        }

        // Check the top frames (up to 5 deep) and collect all matching categories
        // We want the most specific category, which is the first non-generic match
        Category foundCategory = null;
        int maxDepth = Math.min(5, thread.stackTrace().size());

        for (int i = 0; i < maxDepth; i++) {
            StackFrame frame = thread.stackTrace().get(i);
            for (CategoryRule rule : RULES) {
                if (rule.matches(frame)) {
                    // Found a match - return it immediately as rules are ordered by specificity
                    // (most specific first: Network, External Process, Socket, I/O, Lock, Sleep, Park)
                    return rule.category;
                }
            }
        }

        // If no specific category matches, check thread state
        if (thread.state() == Thread.State.RUNNABLE) {
            return Category.COMPUTATION;
        }

        return Category.UNKNOWN;
    }

    /**
     * Categorizes multiple occurrences of a thread and returns a distribution.
     *
     * @param threads List of thread infos (same thread across multiple dumps)
     * @return Map of categories to their occurrence count
     */
    public static Map<Category, Integer> categorizeMultiple(List<ThreadInfo> threads) {
        Map<Category, Integer> distribution = new EnumMap<>(Category.class);
        for (ThreadInfo thread : threads) {
            Category category = categorize(thread);
            distribution.put(category, distribution.getOrDefault(category, 0) + 1);
        }
        return distribution;
    }

    /**
     * Formats a category distribution as a human-readable string.
     *
     * @param distribution Map of categories to counts
     * @param totalCount Total number of occurrences
     * @return Formatted string (e.g., "Computation: 80%, I/O Read: 20%")
     */
    public static String formatDistribution(Map<Category, Integer> distribution, int totalCount) {
        if (distribution.isEmpty() || totalCount == 0) {
            return "";
        }

        // Sort by count (descending)
        List<Map.Entry<Category, Integer>> sorted = distribution.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .toList();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<Category, Integer> entry = sorted.get(i);
            double percentage = (entry.getValue() * 100.0) / totalCount;

            if (i > 0) sb.append(", ");

            // If 100% of one category, just show the name
            if (sorted.size() == 1 && percentage == 100.0) {
                sb.append(entry.getKey().getDisplayName());
            } else {
                sb.append(String.format(Locale.US, "%s: %.0f%%",
                    entry.getKey().getDisplayName(), percentage));
            }
        }

        return sb.toString();
    }

    /**
     * A rule for matching stack frames to categories
     */
    private record CategoryRule(Category category, Predicate<StackFrame> predicate) {
        boolean matches(StackFrame frame) {
            return predicate.test(frame);
        }
    }
}