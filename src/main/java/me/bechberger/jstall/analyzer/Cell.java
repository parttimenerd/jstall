package me.bechberger.jstall.analyzer;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A table cell: sealed interface with typed subclasses for proper modelling.
 * Each subclass encapsulates its value and formatting logic.
 */
public sealed interface Cell permits Cell.TextCell, Cell.IntegerCell, Cell.BytesCell, Cell.NumberCell {

    /** The formatted text shown to the user (e.g. "1.23s", "45.0%", "Thread-1"). */
    String display();

    /** Optional numeric sort key (null for text-only cells). */
    Double sortValue();

    /** Optional color hint for live mode rendering (null = default/no color). */
    Color color();

    /** ANSI color hints for live-mode rendering. Ignored in non-interactive output. */
    enum Color {
        RED("\033[31m"),
        GREEN("\033[32m"),
        YELLOW("\033[33m"),
        BLUE("\033[34m"),
        MAGENTA("\033[35m"),
        CYAN("\033[36m"),
        WHITE("\033[37m"),
        BRIGHT_RED("\033[91m"),
        BRIGHT_GREEN("\033[92m"),
        BRIGHT_YELLOW("\033[93m");

        private final String ansi;

        Color(String ansi) { this.ansi = ansi; }

        public String ansi() { return ansi; }
        public static String reset() { return "\033[0m"; }
    }

    // -------------------------------------------------------------------------
    // Subclasses
    // -------------------------------------------------------------------------

    /** Pure text cell, no numeric sort value. */
    record TextCell(String text, Color color) implements Cell {
        @Override public String display() { return text; }
        @Override public Double sortValue() { return null; }
    }

    /** Integer cell: displays the raw long value, sorts numerically. */
    record IntegerCell(long value, Color color) implements Cell {
        @Override public String display() { return String.valueOf(value); }
        @Override public Double sortValue() { return (double) value; }
    }

    /** Byte-size cell: displays human-readable (KB/MB/GB), sorts by raw bytes. */
    record BytesCell(long bytes, Color color) implements Cell {
        @Override public String display() { return formatBytes(bytes); }
        @Override public Double sortValue() { return (double) bytes; }
    }

    /** Custom-formatted numeric cell: caller provides display string + sort value. */
    record NumberCell(String formatted, double value, Color color) implements Cell {
        @Override public String display() { return formatted; }
        @Override public Double sortValue() { return value; }
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /** Creates a text-only cell (no numeric sort value). */
    static Cell text(String display) {
        return new TextCell(display == null ? "" : display, null);
    }

    /** Creates a text cell with a color hint. */
    static Cell text(String display, Color color) {
        return new TextCell(display == null ? "" : display, color);
    }

    /** Creates an integer cell that displays the value and sorts numerically. */
    static Cell integer(long value) {
        return new IntegerCell(value, null);
    }

    /** Creates an integer cell with a color hint. */
    static Cell integer(long value, Color color) {
        return new IntegerCell(value, color);
    }

    /** Creates a byte-size cell that displays human-readable format and sorts by raw bytes. */
    static Cell bytes(long value) {
        return new BytesCell(value, null);
    }

    /** Creates a byte-size cell with a color hint. */
    static Cell bytes(long value, Color color) {
        return new BytesCell(value, color);
    }

    /** Creates a cell with a custom display string and explicit numeric sort value. */
    static Cell number(String display, double value) {
        return new NumberCell(display, value, null);
    }

    /** Creates a custom-formatted numeric cell with a color hint. */
    static Cell number(String display, double value, Color color) {
        return new NumberCell(display, value, color);
    }

    /**
     * Creates a cell that auto-detects a numeric sort value from common patterns
     * like "1.23s", "45.0%", or plain numbers. Falls back to text-only if no pattern matches.
     */
    static Cell of(String display) {
        if (display == null || display.isBlank()) return new TextCell(display == null ? "" : display, null);
        Double value = Patterns.extractNumeric(display);
        if (value != null) return new NumberCell(display, value, null);
        return new TextCell(display, null);
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /** Compares two cells: by sortValue if both are numeric, otherwise by display string. */
    static int compare(Cell a, Cell b) {
        if (a.sortValue() != null && b.sortValue() != null) {
            return Double.compare(a.sortValue(), b.sortValue());
        }
        return a.display().compareToIgnoreCase(b.display());
    }

    /** Format bytes to two-decimal human-readable string (GB / MB / KB / bytes). */
    static String formatBytes(long bytes) {
        if (bytes < 0) return "0 bytes";
        if (bytes >= 1_024L * 1_024 * 1_024)
            return String.format(Locale.ROOT, "%.2f GB", bytes / (1_024.0 * 1_024 * 1_024));
        if (bytes >= 1_024L * 1_024)
            return String.format(Locale.ROOT, "%.2f MB", bytes / (1_024.0 * 1_024));
        if (bytes >= 1_024L)
            return String.format(Locale.ROOT, "%.2f KB", bytes / 1_024.0);
        return bytes + " bytes";
    }

    /** Internal helper for pattern-based numeric extraction (interfaces can't have private fields). */
    final class Patterns {
        private Patterns() {}

        private static final Pattern DURATION_PATTERN = Pattern.compile("^\\s*([+-]?\\d+\\.?\\d*)s$");
        private static final Pattern PERCENT_PATTERN = Pattern.compile("^\\s*([+-]?\\d+\\.?\\d*)%$");
        private static final Pattern COMMA_NUMBER_PATTERN = Pattern.compile("^\\s*([+-]?\\d{1,3}(?:,\\d{3})+)(.*)$");
        private static final Pattern NUMBER_PATTERN = Pattern.compile("^\\s*([+-]?\\d+\\.?\\d*).*$");

        static Double extractNumeric(String s) {
            String trimmed = s.trim();
            Matcher m;
            m = DURATION_PATTERN.matcher(trimmed);
            if (m.matches()) return parseDoubleOrNull(m.group(1));
            m = PERCENT_PATTERN.matcher(trimmed);
            if (m.matches()) return parseDoubleOrNull(m.group(1));
            // Check for comma-separated thousands (e.g. "15,177" or "72,742,912")
            m = COMMA_NUMBER_PATTERN.matcher(trimmed);
            if (m.matches()) return parseDoubleOrNull(m.group(1).replace(",", ""));
            m = NUMBER_PATTERN.matcher(trimmed);
            if (m.matches()) return parseDoubleOrNull(m.group(1));
            return null;
        }

        private static Double parseDoubleOrNull(String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
