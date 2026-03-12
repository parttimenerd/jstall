package me.bechberger.jstall.util;

import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for {@code jcmd <pid> Compiler.queue} output.
 * <p>
 * Grammar based on JDK's CompileBroker::print_compile_queues output:
 * <pre>
 * Current compiles:
 *   [thread lines showing active compilations]
 *
 * [Queue name] compile queue:
 *   [task lines or "Empty"]
 * </pre>
 */
public final class CompilerQueueParser {

    private CompilerQueueParser() {
    }

    /**
     * Parse Compiler.queue output into structured snapshot.
     *
     * @param output raw jcmd output
     * @return parsed snapshot, or null if output is empty/malformed
     */
    @Nullable
    public static CompilerQueueSnapshot parse(@Nullable String output) {
        if (output == null || output.isBlank()) {
            return null;
        }

        String[] lines = output.split("\\R");
        int lineIndex = 0;

        // Skip leading pid lines like "12345:"
        while (lineIndex < lines.length && lines[lineIndex].matches("^\\d+:\\s*$")) {
            lineIndex++;
        }

        if (lineIndex >= lines.length) {
            return null;
        }

        // Expect "Current compiles:" header
        if (!lines[lineIndex].trim().equals("Current compiles:")) {
            return null;
        }
        lineIndex++;

        // Parse active compiler threads
        List<CompileTask> activeCompiles = new ArrayList<>();
        while (lineIndex < lines.length) {
            String line = lines[lineIndex];
            if (line.isBlank()) {
                lineIndex++;
                break; // blank line separates sections
            }
            if (line.contains(" compile queue:")) {
                break; // reached queue blocks
            }

            // Thread line format: "ThreadName  <task_line>"
            // Look for double-space separator
            int doubleSp = line.indexOf("  ");
            if (doubleSp > 0) {
                String taskPart = line.substring(doubleSp + 2).trim();
                CompileTask task = parseTaskLine(taskPart);
                if (task != null) {
                    activeCompiles.add(task);
                }
            }
            lineIndex++;
        }

        // Parse queue blocks
        Map<String, List<CompileTask>> queuesByName = new LinkedHashMap<>();
        while (lineIndex < lines.length) {
            String line = lines[lineIndex].trim();
            if (line.isBlank()) {
                lineIndex++;
                continue;
            }

            // Queue header: "C1 compile queue:", "C2 compile queue:", etc.
            if (line.endsWith(" compile queue:")) {
                String queueName = line.substring(0, line.length() - 15).trim();
                lineIndex++;

                List<CompileTask> queueTasks = new ArrayList<>();
                
                // Check for "Empty"
                if (lineIndex < lines.length && lines[lineIndex].trim().equals("Empty")) {
                    lineIndex++;
                    queuesByName.put(queueName, queueTasks);
                    continue;
                }

                // Parse task lines until blank or next queue
                while (lineIndex < lines.length) {
                    String taskLine = lines[lineIndex];
                    if (taskLine.isBlank()) {
                        lineIndex++;
                        break;
                    }
                    if (taskLine.contains(" compile queue:")) {
                        break; // next queue
                    }

                    CompileTask task = parseTaskLine(taskLine.trim());
                    if (task != null) {
                        queueTasks.add(task);
                    }
                    lineIndex++;
                }

                queuesByName.put(queueName, queueTasks);
            } else {
                lineIndex++;
            }
        }

        return new CompilerQueueSnapshot(activeCompiles, queuesByName);
    }

    // Task line starts with compile_id followed by optional flags/tier and method descriptor.
    private static final Pattern COMPILE_ID_PATTERN = Pattern.compile("^\\s*(\\d+)\\s+(.*)$");
    private static final Pattern TIER_AT_END_PATTERN = Pattern.compile("^(.*?)(\\d+|-)\\s*$");

    @Nullable
    private static CompileTask parseTaskLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        Matcher m = COMPILE_ID_PATTERN.matcher(line);
        if (!m.matches()) {
            return null;
        }

        int compileId = Integer.parseInt(m.group(1));
        String remainder = m.group(2).trim();

        int methodStartTokenIndex = findMethodStartTokenIndex(remainder);
        if (methodStartTokenIndex < 0) {
            return null;
        }

        String[] tokens = remainder.split("\\s+");
        String prefix = String.join(" ", Arrays.copyOfRange(tokens, 0, methodStartTokenIndex)).trim();
        String methodPart = String.join(" ", Arrays.copyOfRange(tokens, methodStartTokenIndex, tokens.length)).trim();

        String attributesSection = prefix;
        String tierStr = null;
        if (!prefix.isEmpty()) {
            Matcher tierMatcher = TIER_AT_END_PATTERN.matcher(prefix);
            if (tierMatcher.matches()) {
                attributesSection = tierMatcher.group(1).trim();
                tierStr = tierMatcher.group(2);
            }
        }
        
        // Parse flags from attributes section
        boolean isOsr = attributesSection.contains("%");
        boolean isSynchronized = attributesSection.contains("s");
        boolean hasExceptionHandler = attributesSection.contains("!");
        boolean isBlocking = attributesSection.contains("b");
        boolean isNative = attributesSection.contains("n");
        
        Integer tier = null;
        if (tierStr != null && !"-".equals(tierStr)) {
            try {
                tier = Integer.parseInt(tierStr);
            } catch (NumberFormatException ignored) {
            }
        }
        
        // Parse method descriptor
        // Format: methodName [ @ osrBci ] (native | bytes bytes) [ message ]
        String methodName = null;
        Integer osrBci = null;
        Integer bytes = null;
        boolean isNativeMethod = false;

        if (methodPart.equals("(method)")) {
            methodName = "(method)";
        } else {
            // Extract method name (up to @ or '(')
            int atPos = methodPart.indexOf(" @ ");
            int parenPos = methodPart.indexOf(" (");
            
            if (atPos > 0 && (parenPos < 0 || atPos < parenPos)) {
                methodName = methodPart.substring(0, atPos);
                int parenAfterAt = methodPart.indexOf(" (", atPos);
                if (parenAfterAt > 0) {
                    String bciPart = methodPart.substring(atPos + 3, parenAfterAt);
                    try {
                        osrBci = Integer.parseInt(bciPart.trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            } else if (parenPos > 0) {
                methodName = methodPart.substring(0, parenPos);
            } else {
                methodName = methodPart;
            }

            // Parse (native) or (N bytes)
            int openParen = methodPart.indexOf('(');
            if (openParen >= 0) {
                int closeParen = methodPart.indexOf(')', openParen);
                if (closeParen > openParen) {
                    String paren = methodPart.substring(openParen + 1, closeParen).trim();
                    if ("native".equals(paren)) {
                        isNativeMethod = true;
                    } else {
                        String[] parts = paren.split("\\s+");
                        if (parts.length == 2 && "bytes".equals(parts[1])) {
                            try {
                                bytes = Integer.parseInt(parts[0]);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
            }
        }

        return new CompileTask(
            compileId,
            isOsr,
            isSynchronized,
            hasExceptionHandler,
            isBlocking,
            isNative,
            tier,
                methodName,
            osrBci,
            bytes,
            isNativeMethod
        );
    }

    private static int findMethodStartTokenIndex(String remainder) {
        if (remainder == null || remainder.isBlank()) {
            return -1;
        }
        String[] tokens = remainder.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.isEmpty()) {
                continue;
            }
            if (token.startsWith("(") || token.contains(".") || token.contains("/") || token.contains("::") || token.contains("$")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Snapshot of compiler queue state at a point in time.
     */
    public record CompilerQueueSnapshot(
        List<CompileTask> activeCompiles,
        Map<String, List<CompileTask>> queuesByName
    ) {
        public int totalActiveCount() {
            return activeCompiles.size();
        }

        public int totalQueuedCount() {
            return queuesByName.values().stream().mapToInt(List::size).sum();
        }

        public int queuedCountForQueue(String queueName) {
            List<CompileTask> tasks = queuesByName.get(queueName);
            return tasks != null ? tasks.size() : 0;
        }
    }

    /**
     * Represents a single compilation task.
     */
    public record CompileTask(
        int compileId,
        boolean isOsr,
        boolean isSynchronized,
        boolean hasExceptionHandler,
        boolean isBlocking,
        boolean isNative,
        Integer tier,
        String methodName,
        Integer osrBci,
        Integer bytes,
        boolean isNativeMethod
    ) {
        public String formatFlags() {
            StringBuilder sb = new StringBuilder();
            if (isOsr) sb.append("OSR ");
            if (isSynchronized) sb.append("sync ");
            if (hasExceptionHandler) sb.append("exc ");
            if (isBlocking) sb.append("blocking ");
            if (isNative) sb.append("native ");
            return sb.toString().trim();
        }
    }
}