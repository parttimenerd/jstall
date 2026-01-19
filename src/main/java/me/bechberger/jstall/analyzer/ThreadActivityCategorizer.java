package me.bechberger.jstall.analyzer;

import me.bechberger.jthreaddump.model.StackFrame;
import me.bechberger.jthreaddump.model.ThreadInfo;

import java.util.*;
import java.util.function.Predicate;

import static me.bechberger.jstall.analyzer.ThreadActivityCategorizer.RuleBuilder.*;

/**
 * Categorizes thread activity based on stack traces.
 *
 * <p>This categorizer analyzes the top stack frames (up to depth 5) to determine
 * what kind of work a thread is performing. Categories are checked in order of
 * specificity, with more specific categories (e.g., Network Read) checked before
 * generic ones (e.g., I/O).</p>
 *
 * <p>The categorizer is designed to be extensible - new categories and rules can
 * be added easily by extending the initialization block.</p>
 */
public class ThreadActivityCategorizer {

    /** Maximum depth to examine in stack traces */
    private static final int MAX_STACK_DEPTH = 5;

    /**
     * Internal DSL for building categorization rules.
     */
    public static class RuleBuilder {
        public static ClassMatcher className() {
            return new ClassMatcher();
        }

        public static MethodMatcher methodName() {
            return new MethodMatcher();
        }

        public static Predicate<StackFrame> anyOf(Predicate<StackFrame>... predicates) {
            return frame -> {
                for (Predicate<StackFrame> p : predicates) {
                    if (p.test(frame)) return true;
                }
                return false;
            };
        }

        public static Predicate<StackFrame> allOf(Predicate<StackFrame>... predicates) {
            return frame -> {
                for (Predicate<StackFrame> p : predicates) {
                    if (!p.test(frame)) return false;
                }
                return true;
            };
        }

        public static class ClassMatcher {
            public Predicate<StackFrame> equals(String name) {
                return frame -> frame.className().equals(name);
            }

            public Predicate<StackFrame> startsWith(String prefix) {
                return frame -> frame.className().startsWith(prefix);
            }

            public Predicate<StackFrame> contains(String substring) {
                return frame -> frame.className().contains(substring);
            }

            public Predicate<StackFrame> in(String... names) {
                return frame -> {
                    for (String name : names) {
                        if (frame.className().equals(name)) return true;
                    }
                    return false;
                };
            }
        }

        public static class MethodMatcher {
            public Predicate<StackFrame> equals(String name) {
                return frame -> frame.methodName().equals(name);
            }

            public Predicate<StackFrame> contains(String substring) {
                return frame -> frame.methodName().contains(substring);
            }

            public Predicate<StackFrame> startsWith(String prefix) {
                return frame -> frame.methodName().startsWith(prefix);
            }

            public Predicate<StackFrame> in(String... names) {
                return frame -> {
                    for (String name : names) {
                        if (frame.methodName().equals(name)) return true;
                    }
                    return false;
                };
            }
        }
    }

    /**
     * High-level groupings of thread activity categories.
     */
    public enum CategoryGroup {
        NETWORKING("Networking"),
        IO("I/O"),
        LOCKING("Locking"),
        JAVA("Java"),
        NATIVE("Native"),
        COMPUTATION("Computation"),
        UNKNOWN("Unknown");

        private final String displayName;

        CategoryGroup(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Categories of thread activity.
     * Each category contains its own matching rule and belongs to a {@link CategoryGroup}.
     * Categories are ordered by specificity - more specific categories first.
     */
    public enum Category {
        // External Process operations (most specific)
        EXTERNAL_PROCESS("External Process", CategoryGroup.JAVA, anyOf(
            className().in("java.lang.ProcessHandleImpl", "java.lang.ProcessImpl"),
            className().startsWith("java.lang.Process"),
            allOf(
                className().contains("Process"),
                anyOf(methodName().contains("wait"), methodName().contains("reaper"))
            )
        )),

        // Database operations
        DB("Database", CategoryGroup.IO, anyOf(
            className().startsWith("java.sql."),
            className().startsWith("javax.sql."),
            className().contains("jdbc"),
            className().contains("JDBC"),
            className().contains(".sql."),
            allOf(
                className().contains("ResultSet"),
                frame -> !frame.className().contains("nio")
            )
        )),

        // AWT/UI operations
        AWT("AWT/UI", CategoryGroup.JAVA, anyOf(
            className().startsWith("java.awt."),
            className().startsWith("javax.swing."),
            className().startsWith("sun.awt."),
            className().startsWith("sun.java2d."),
            className().contains("EventQueue"),
            className().contains("EventDispatch"),
            className().contains("AWTAutoShutdown"),
            className().contains("Java2D"),
            allOf(
                className().contains("AWT"),
                anyOf(
                    className().contains("AppKit"),
                    className().contains("Shutdown"),
                    className().contains("Threading")
                )
            )
        )),

        // Timer operations
        TIMER("Timer", CategoryGroup.JAVA, anyOf(
            className().equals("java.util.TimerThread"),
            allOf(
                className().equals("java.util.Timer"),
                methodName().contains("mainLoop")
            )
        )),

        // Virtual Thread operations (Java 21+)
        VIRTUAL_THREAD("Virtual Thread", CategoryGroup.JAVA, anyOf(
            className().equals("java.lang.VirtualThread"),
            className().equals("jdk.internal.vm.Continuation"),
            allOf(
                className().startsWith("java.util.concurrent.ForkJoin"),
                frame -> frame.className().contains("VirtualThread") ||
                         (frame.methodName() != null && frame.methodName().contains("Continuation"))
            )
        )),

        // ForkJoinPool operations (parallel streams, virtual threads)
        FORK_JOIN("ForkJoin Pool", CategoryGroup.JAVA, anyOf(
            className().startsWith("java.util.concurrent.ForkJoin"),
            allOf(
                className().contains("ForkJoin"),
                anyOf(
                    methodName().contains("scan"),
                    methodName().contains("runWorker"),
                    methodName().contains("topLevelExec"),
                    methodName().contains("compute")
                )
            )
        )),

        // Network Read - specific socket/selector read operations
        NETWORK_READ("Network Read", CategoryGroup.NETWORKING, frame ->
            isSocketRead(frame) ||
            isUnixDomainSocketOperation(frame, "accept", "read") ||
            isNettyOperation(frame, "read")
        ),

        // Network Write - specific socket write operations
        NETWORK_WRITE("Network Write", CategoryGroup.NETWORKING, frame ->
            isSocketWrite(frame) ||
            isNettyOperation(frame, "write")
        ),

        // Network - general networking (selectors, polling, monitoring)
        NETWORK("Network", CategoryGroup.NETWORKING, frame ->
            isNioSelector(frame) ||
            isNettyChannel(frame) ||
            isWatchService(frame)
        ),

        // I/O Read - file input operations
        IO_READ("I/O Read", CategoryGroup.IO, frame ->
            isFileRead(frame) ||
            isInputStreamRead(frame) ||
            isReaderRead(frame) ||
            isFileChannelRead(frame)
        ),

        // I/O Write - file output operations
        IO_WRITE("I/O Write", CategoryGroup.IO, frame ->
            isFileWrite(frame) ||
            isOutputStreamWrite(frame) ||
            isWriterWrite(frame) ||
            isFileChannelWrite(frame)
        ),

        // Generic I/O - general file/channel operations
        IO("I/O", CategoryGroup.IO, anyOf(
            className().startsWith("java.io"),
            className().startsWith("java.nio.file"),
            allOf(
                className().contains("Channel"),
                frame -> !frame.className().contains("Socket"),
                frame -> !frame.className().contains("netty")
            )
        )),

        // Lock Wait - threads waiting for locks/monitors
        LOCK_WAIT("Lock Wait", CategoryGroup.LOCKING, frame ->
            isUnsafeParkOrWait(frame) ||
            isLockSupportPark(frame) ||
            isObjectWait(frame)
        ),

        // Sleep - explicit sleep/yield calls
        SLEEP("Sleep", CategoryGroup.LOCKING, allOf(
            className().equals("java.lang.Thread"),
            methodName().in("sleep", "yield")
        )),

        // Park - lock support parking
        PARK("Park", CategoryGroup.LOCKING, allOf(
            className().equals("java.util.concurrent.locks.LockSupport"),
            methodName().startsWith("park")
        )),

        // Native - JNI/native method calls
        NATIVE("Native", CategoryGroup.NATIVE, ThreadActivityCategorizer::isNativeMethod),

        // Computation - fallback for RUNNABLE state (no predicate needed)
        COMPUTATION("Computation", CategoryGroup.COMPUTATION, null),

        // Unknown - default fallback (no predicate needed)
        UNKNOWN("Unknown", CategoryGroup.UNKNOWN, null);

        private final String displayName;
        private final CategoryGroup group;
        private final Predicate<StackFrame> predicate;

        Category(String displayName, CategoryGroup group, Predicate<StackFrame> predicate) {
            this.displayName = displayName;
            this.group = group;
            this.predicate = predicate;
        }

        public String getDisplayName() {
            return displayName;
        }

        public CategoryGroup getGroup() {
            return group;
        }

        /**
         * Tests if this category matches the given stack frame.
         *
         * @param frame The stack frame to test
         * @return true if this category matches the frame
         */
        public boolean matches(StackFrame frame) {
            return predicate != null && predicate.test(frame);
        }
    }

    // ========== Helper Methods for Network Operations ==========

    private static boolean isSocketRead(StackFrame frame) {
        return (frame.className().contains("Socket") &&
                (frame.methodName().contains("read") ||
                 frame.methodName().contains("Read") ||
                 frame.methodName().equals("accept"))) ||
               (frame.className().equals("sun.nio.ch.SocketChannelImpl") &&
                (frame.methodName().equals("read") || frame.methodName().contains("Read"))) ||
               (frame.className().equals("java.net.SocketInputStream") &&
                frame.methodName().contains("read")) ||
               (frame.className().contains("ServerSocket") &&
                frame.methodName().equals("accept"));
    }

    private static boolean isSocketWrite(StackFrame frame) {
        return (frame.className().contains("Socket") &&
                (frame.methodName().contains("write") || frame.methodName().contains("Write"))) ||
               (frame.className().equals("sun.nio.ch.SocketChannelImpl") &&
                (frame.methodName().equals("write") || frame.methodName().contains("Write"))) ||
               (frame.className().equals("java.net.SocketOutputStream") &&
                frame.methodName().contains("write"));
    }

    private static boolean isUnixDomainSocketOperation(StackFrame frame, String... operations) {
        if (!frame.className().equals("sun.nio.ch.UnixDomainSockets")) {
            return false;
        }
        for (String op : operations) {
            if (frame.methodName().contains(op)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNettyOperation(StackFrame frame, String operation) {
        return frame.className().startsWith("io.netty.channel") &&
               frame.methodName().contains(operation);
    }

    private static boolean isNettyChannel(StackFrame frame) {
        return (frame.className().startsWith("io.netty.channel.nio") &&
                !frame.methodName().contains("read") &&
                !frame.methodName().contains("write")) ||
               frame.className().startsWith("io.netty.channel.epoll") ||
               frame.className().startsWith("io.netty.channel.kqueue");
    }

    private static boolean isNioSelector(StackFrame frame) {
        return (frame.className().contains("Selector") &&
                (frame.methodName().contains("select") || frame.methodName().contains("poll"))) ||
               (frame.className().equals("sun.nio.ch.KQueue") && frame.methodName().equals("poll")) ||
               (frame.className().equals("sun.nio.ch.EPoll") && frame.methodName().contains("wait")) ||
               (frame.className().equals("sun.nio.ch.WindowsSelectorImpl") &&
                frame.methodName().contains("doSelect")) ||
               (frame.className().contains("KQueueSelectorImpl") &&
                frame.methodName().contains("doSelect")) ||
               (frame.className().contains("EPollSelectorImpl") &&
                frame.methodName().contains("doSelect"));
    }

    private static boolean isWatchService(StackFrame frame) {
        return frame.className().contains("WatchService") &&
               (frame.methodName().equals("take") || frame.methodName().equals("poll"));
    }

    // ========== Helper Methods for I/O Operations ==========

    private static boolean isFileRead(StackFrame frame) {
        return frame.className().startsWith("java.io") &&
               (frame.methodName().contains("read") || frame.methodName().contains("Read"));
    }

    private static boolean isInputStreamRead(StackFrame frame) {
        return frame.className().contains("InputStream") &&
               !frame.className().contains("Socket") &&
               frame.methodName().contains("read");
    }

    private static boolean isReaderRead(StackFrame frame) {
        return frame.className().contains("Reader") &&
               frame.methodName().contains("read");
    }

    private static boolean isFileChannelRead(StackFrame frame) {
        return (frame.className().equals("sun.nio.ch.FileChannelImpl") ||
                frame.className().equals("java.nio.channels.FileChannel")) &&
               (frame.methodName().equals("read") || frame.methodName().contains("read"));
    }

    private static boolean isFileWrite(StackFrame frame) {
        return frame.className().startsWith("java.io") &&
               (frame.methodName().contains("write") || frame.methodName().contains("Write") ||
                frame.methodName().contains("flush") || frame.methodName().contains("Flush"));
    }

    private static boolean isOutputStreamWrite(StackFrame frame) {
        return frame.className().contains("OutputStream") &&
               !frame.className().contains("Socket") &&
               (frame.methodName().contains("write") || frame.methodName().contains("flush"));
    }

    private static boolean isWriterWrite(StackFrame frame) {
        return frame.className().contains("Writer") &&
               (frame.methodName().contains("write") || frame.methodName().contains("flush"));
    }

    private static boolean isFileChannelWrite(StackFrame frame) {
        return (frame.className().equals("sun.nio.ch.FileChannelImpl") ||
                frame.className().equals("java.nio.channels.FileChannel")) &&
               (frame.methodName().equals("write") || frame.methodName().contains("write"));
    }

    // ========== Helper Methods for Threading Operations ==========

    private static boolean isUnsafeParkOrWait(StackFrame frame) {
        return (frame.className().equals("jdk.internal.misc.Unsafe") ||
                frame.className().equals("sun.misc.Unsafe")) &&
               (frame.methodName().equals("park") || frame.methodName().contains("wait"));
    }

    private static boolean isLockSupportPark(StackFrame frame) {
        return frame.className().equals("java.util.concurrent.locks.LockSupport") &&
               frame.methodName().equals("park");
    }

    private static boolean isObjectWait(StackFrame frame) {
        return frame.className().equals("java.lang.Object") &&
               frame.methodName().equals("wait");
    }

    // ========== Helper Methods for Native Operations ==========

    private static boolean isNativeMethod(StackFrame frame) {
        // Check if the method is marked as a native method
        return Boolean.TRUE.equals(frame.nativeMethod());
    }

    /**
     * Categorizes a thread based on its stack trace.
     *
     * <p>Examines the top frames (up to {@link #MAX_STACK_DEPTH}) to determine
     * the thread's activity. Returns the most specific category found.</p>
     *
     * <p>Categories are checked in enum declaration order, which is ordered by
     * specificity (most specific first). If the top frame is uncategorized but
     * a frame below it matches a category, that category is returned.</p>
     *
     * @param thread The thread to categorize
     * @return The category of activity, never null
     */
    public static Category categorize(ThreadInfo thread) {
        if (thread.stackTrace() == null || thread.stackTrace().isEmpty()) {
            return Category.UNKNOWN;
        }

        // Scan top frames for first matching category
        // Categories are ordered by specificity in enum declaration, so first match is most accurate
        int maxDepth = Math.min(MAX_STACK_DEPTH, thread.stackTrace().size());

        for (int i = 0; i < maxDepth; i++) {
            StackFrame frame = thread.stackTrace().get(i);
            for (Category category : Category.values()) {
                if (category.matches(frame)) {
                    return category;
                }
            }
        }

        // No specific category found - check thread state as fallback
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