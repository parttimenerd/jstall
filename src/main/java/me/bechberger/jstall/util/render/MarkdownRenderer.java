package me.bechberger.jstall.util.render;

import java.util.function.Consumer;

/**
 * Streaming line-based markdown-to-ANSI renderer.
 *
 * <p>Wraps a downstream {@code Consumer<String>} and accepts LLM response tokens
 * one chunk at a time. Buffers until a newline is detected, then renders the
 * complete line with ANSI styling before forwarding it.
 *
 * <p>Supports:
 * <ul>
 *   <li>ATX headers (# through ####)</li>
 *   <li>Fenced code blocks (```lang)</li>
 *   <li>Blockquotes (> )</li>
 *   <li>Unordered lists (- , * , + )</li>
 *   <li>Ordered lists (1. )</li>
 *   <li>Horizontal rules (--- / *** / ___)</li>
 *   <li>Inline: **bold**, *italic*, _italic_, `code`, [text](url)</li>
 * </ul>
 *
 * <p>Call {@link #flush()} when the stream ends to emit any buffered tail.
 */
public class MarkdownRenderer {

    private final Consumer<String> downstream;
    private final StringBuilder buf = new StringBuilder();
    private boolean inCodeFence = false;
    private String codeFenceLang = "";

    public MarkdownRenderer(Consumer<String> downstream) {
        this.downstream = downstream;
    }

    /** Accept the next token from the LLM stream. */
    public void accept(String token) {
        buf.append(token);
        int newline;
        while ((newline = buf.indexOf("\n")) >= 0) {
            String line = buf.substring(0, newline);
            buf.delete(0, newline + 1);
            emit(renderLine(line));
        }
    }

    /** Flush any buffered content that hasn't ended with a newline. */
    public void flush() {
        if (!buf.isEmpty()) {
            emit(renderLine(buf.toString()));
            buf.setLength(0);
        }
        // Close any open code fence
        if (inCodeFence) {
            emit(AnsiCodes.RESET);
            inCodeFence = false;
        }
    }

    // -------------------------------------------------------------------------

    private String renderLine(String line) {
        if (inCodeFence) {
            if (line.startsWith("```") || line.startsWith("~~~")) {
                inCodeFence = false;
                codeFenceLang = "";
                return AnsiCodes.RESET;
            }
            // Inside code fence — pass through with dim color
            return AnsiCodes.FG_CYAN + line + AnsiCodes.RESET;
        }

        // Opening code fence
        if (line.startsWith("```") || line.startsWith("~~~")) {
            inCodeFence = true;
            codeFenceLang = line.substring(3).trim();
            String label = codeFenceLang.isEmpty() ? "code" : codeFenceLang;
            return AnsiCodes.DIM_ON + AnsiCodes.FG_CYAN + "┌─[" + label + "]" + AnsiCodes.RESET;
        }

        // Horizontal rule
        if (line.matches("^(---+|\\*\\*\\*+|___+)\\s*$")) {
            return AnsiCodes.DIM_ON + "──────────────────────────────────" + AnsiCodes.RESET;
        }

        // ATX headers
        if (line.startsWith("#### ")) return header(line.substring(5), 4);
        if (line.startsWith("### "))  return header(line.substring(4), 3);
        if (line.startsWith("## "))   return header(line.substring(3), 2);
        if (line.startsWith("# "))    return header(line.substring(2), 1);

        // Blockquote
        if (line.startsWith("> ")) {
            return AnsiCodes.DIM_ON + AnsiCodes.FG_GREEN + "│ " + AnsiCodes.RESET
                    + AnsiCodes.DIM_ON + renderInline(line.substring(2)) + AnsiCodes.RESET;
        }

        // Unordered list
        if (line.matches("^(\\s*)[-*+] (.*)")) {
            int indent = countLeadingSpaces(line);
            String content = line.replaceFirst("^\\s*[-*+] ", "");
            String bullet = indent > 0 ? "  ◦ " : "• ";
            return " ".repeat(indent) + AnsiCodes.FG_YELLOW + bullet + AnsiCodes.RESET + renderInline(content);
        }

        // Ordered list
        if (line.matches("^(\\s*)\\d+\\. (.*)")) {
            int indent = countLeadingSpaces(line);
            String content = line.replaceFirst("^\\s*\\d+\\. ", "");
            String number = line.replaceFirst("^(\\s*)(\\d+\\.).*", "$2");
            return " ".repeat(indent) + AnsiCodes.FG_YELLOW + number + " " + AnsiCodes.RESET + renderInline(content);
        }

        // Regular paragraph / empty line
        return renderInline(line);
    }

    private String header(String text, int level) {
        String prefix = switch (level) {
            case 1 -> AnsiCodes.BOLD_ON + AnsiCodes.FG_CYAN;
            case 2 -> AnsiCodes.BOLD_ON + AnsiCodes.FG_YELLOW;
            case 3 -> AnsiCodes.BOLD_ON + AnsiCodes.FG_GREEN;
            default -> AnsiCodes.BOLD_ON;
        };
        String hashes = "#".repeat(level);
        return prefix + hashes + " " + renderInline(text) + AnsiCodes.RESET;
    }

    /** Render inline markdown: bold, italic, inline code, links. */
    static String renderInline(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);

            // Inline code: `...`
            if (c == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i) {
                    out.append(AnsiCodes.FG_CYAN).append(s, i + 1, end).append(AnsiCodes.RESET);
                    i = end + 1;
                    continue;
                }
            }

            // Bold: **...**
            if (c == '*' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("**", i + 2);
                if (end > i + 1) {
                    out.append(AnsiCodes.BOLD_ON)
                       .append(renderInlineNoNest(s.substring(i + 2, end)))
                       .append(AnsiCodes.RESET);
                    i = end + 2;
                    continue;
                }
            }

            // Italic: *...* or _..._
            if ((c == '*' || c == '_') && i + 1 < s.length()) {
                char close = c;
                int end = s.indexOf(close, i + 1);
                if (end > i) {
                    out.append(AnsiCodes.ITALIC_ON)
                       .append(s, i + 1, end)
                       .append(AnsiCodes.RESET);
                    i = end + 1;
                    continue;
                }
            }

            // Link: [text](url)
            if (c == '[') {
                int textEnd = s.indexOf(']', i + 1);
                if (textEnd > i && textEnd + 1 < s.length() && s.charAt(textEnd + 1) == '(') {
                    int urlEnd = s.indexOf(')', textEnd + 2);
                    if (urlEnd > textEnd) {
                        String text = s.substring(i + 1, textEnd);
                        String url = s.substring(textEnd + 2, urlEnd);
                        out.append(AnsiCodes.UNDERLINE_ON).append(text).append(AnsiCodes.RESET);
                        out.append(AnsiCodes.DIM_ON).append(" (").append(url).append(")").append(AnsiCodes.RESET);
                        i = urlEnd + 1;
                        continue;
                    }
                }
            }

            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Inline rendering without recursion (used inside bold spans). */
    private static String renderInlineNoNest(String s) {
        return s; // Just return raw text inside bold — good enough for LLM output
    }

    private static int countLeadingSpaces(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == ' ') n++;
        return n;
    }

    private void emit(String rendered) {
        downstream.accept(rendered + "\n");
    }
}
