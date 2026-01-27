package me.bechberger.jstall.analyzer;

import me.bechberger.jthreaddump.model.StackFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Intelligently filters stack traces to focus on application code and important framework frames.
 *
 * Collapses internal/framework stack frames while preserving important ones and application code.
 */
public class IntelligentStackFilter {

    /**
     * Patterns for framework/internal code that should be collapsed
     */
    private static final List<Pattern> INTERNAL_PATTERNS = List.of(
        // JDK internals
        Pattern.compile("^jdk\\.internal\\..*"),
        Pattern.compile("^sun\\..*"),
        Pattern.compile("^com\\.sun\\..*"),

        // Java core (less important internals)
        Pattern.compile("^java\\.lang\\.invoke\\..*"),
        Pattern.compile("^java\\.lang\\.reflect\\..*"),
        Pattern.compile("^java\\.lang\\.Thread\\.run.*"),

        // Kotlin internals
        Pattern.compile("^kotlin\\..*internal.*"),

        // Common frameworks (internals)
        Pattern.compile("^org\\.springframework\\..*\\.support\\..*"),
        Pattern.compile("^org\\.springframework\\..*\\.internal\\..*"),
        Pattern.compile("^org\\.apache\\.catalina\\..*"),
        Pattern.compile("^org\\.apache\\.coyote\\..*"),
        Pattern.compile("^org\\.apache\\.tomcat\\..*"),

        // Netty internals
        Pattern.compile("^io\\.netty\\.util\\.internal\\..*"),
        Pattern.compile("^io\\.netty\\.channel\\..*\\.AbstractChannel.*"),

        // Proxy and generated code
        Pattern.compile(".*\\$\\$Lambda.*"),
        Pattern.compile(".*\\$Proxy.*"),
        Pattern.compile(".*CGLIB.*"),
        Pattern.compile(".*ByteBuddy.*")
    );

    /**
     * Patterns for important framework code that should always be shown
     */
    private static final List<Pattern> IMPORTANT_PATTERNS = List.of(
        // I/O operations
        Pattern.compile("^java\\.io\\..*InputStream\\.read.*"),
        Pattern.compile("^java\\.io\\..*OutputStream\\.write.*"),
        Pattern.compile("^java\\.nio\\..*\\.read.*"),
        Pattern.compile("^java\\.nio\\..*\\.write.*"),

        // Network operations
        Pattern.compile("^java\\.net\\..*"),
        Pattern.compile("^sun\\.nio\\.ch\\..*Selector.*"),
        Pattern.compile("^sun\\.nio\\.ch\\..*Socket.*"),
        Pattern.compile("^io\\.netty\\.channel\\.nio\\..*"),

        // Threading
        Pattern.compile("^java\\.util\\.concurrent\\..*Executor.*"),
        Pattern.compile("^java\\.util\\.concurrent\\.locks\\..*"),

        // Database
        Pattern.compile("^java\\.sql\\..*"),
        Pattern.compile("^javax\\.sql\\..*"),

        // HTTP/Web
        Pattern.compile("^javax\\.servlet\\..*"),
        Pattern.compile("^org\\.springframework\\.web\\..*"),

        // Process operations
        Pattern.compile("^java\\.lang\\.Process.*")
    );

    /**
     * Filters a stack trace intelligently, collapsing internal frames while keeping important ones.
     *
     * @param frames The stack frames to filter
     * @param maxRelevantFrames Maximum number of relevant frames to show
     * @return Filtered list of frames and collapsed regions
     */
    public static List<FilteredFrame> filterStackTrace(List<StackFrame> frames, int maxRelevantFrames) {
        List<FilteredFrame> result = new ArrayList<>();

        int relevantCount = 0;
        int consecutiveInternal = 0;

        for (int i = 0; i < frames.size(); i++) {
            StackFrame frame = frames.get(i);
            String fullName = frame.className() + "." + frame.methodName();

            boolean isImportant = isImportantFrame(fullName);
            boolean isInternal = !isImportant && isInternalFrame(fullName);
            boolean isApplication = !isImportant && !isInternal;

            // Always show application code and important frames
            if (isApplication || isImportant) {
                // Add collapsed marker if we had internal frames
                if (consecutiveInternal > 0) {
                    result.add(new FilteredFrame(null, consecutiveInternal, true));
                    consecutiveInternal = 0;
                }

                // Check if we've reached the limit
                if (relevantCount >= maxRelevantFrames) {
                    int remaining = frames.size() - i;
                    if (remaining > 0) {
                        result.add(new FilteredFrame(null, remaining, false));
                    }
                    break;
                }

                result.add(new FilteredFrame(frame, 0, false));
                relevantCount++;
            } else if (isInternal) {
                consecutiveInternal++;
            }
        }

        // Add final collapsed marker if needed
        if (consecutiveInternal > 0) {
            result.add(new FilteredFrame(null, consecutiveInternal, true));
        }

        return result;
    }

    /**
     * Formats filtered stack trace for display.
     *
     * @param filteredFrames The filtered frames
     * @param indent Indentation prefix
     * @return Formatted string
     */
    public static String formatFilteredStackTrace(List<FilteredFrame> filteredFrames, String indent) {
        StringBuilder sb = new StringBuilder();

        for (FilteredFrame filtered : filteredFrames) {
            if (filtered.frame != null) {
                // Regular frame
                sb.append(indent).append(filtered.frame).append("\n");
            } else if (filtered.isCollapsed) {
                // Collapsed internal frames
                sb.append(indent).append("... (")
                  .append(filtered.collapsedCount)
                  .append(" internal frame")
                  .append(filtered.collapsedCount > 1 ? "s" : "")
                  .append(" omitted)\n");
            } else {
                // Remaining frames
                sb.append(indent).append("... (")
                  .append(filtered.collapsedCount)
                  .append(" more frame")
                  .append(filtered.collapsedCount > 1 ? "s" : "")
                  .append(")\n");
            }
        }

        return sb.toString();
    }

    private static boolean isImportantFrame(String fullName) {
        return IMPORTANT_PATTERNS.stream().anyMatch(p -> p.matcher(fullName).matches());
    }

    private static boolean isInternalFrame(String fullName) {
        return INTERNAL_PATTERNS.stream().anyMatch(p -> p.matcher(fullName).matches());
    }

    /**
     * Represents a filtered stack frame or collapsed region.
     */
    public static class FilteredFrame {
        public final StackFrame frame;  // null if collapsed
        public final int collapsedCount;  // number of collapsed frames
        public final boolean isCollapsed;  // true if this represents internal frames, false if just "more"

        FilteredFrame(StackFrame frame, int collapsedCount, boolean isCollapsed) {
            this.frame = frame;
            this.collapsedCount = collapsedCount;
            this.isCollapsed = isCollapsed;
        }
    }
}