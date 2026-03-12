package me.bechberger.jstall.util;

import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Utility parsers for outputs of {@code jcmd} diagnostic commands. */
public final class JcmdOutputParsers {

    private JcmdOutputParsers() {
    }

    /**
     * Parse {@code jcmd <pid> VM.system_properties} output as a map.
     * <p>
     * Ignores the leading {@code <pid>:} line (if present) and the timestamp line starting with {@code #}
     * (e.g. {@code #Mon Feb 09 12:38:52 CET 2026}).
     * <p>
     * Parses {@code key=value} pairs; empty values are allowed. Lines without '=' are ignored.
     */
    public static Map<String, String> parseVmSystemProperties(@Nullable String output) {
        if (output == null || output.isBlank()) {
            return Collections.emptyMap();
        }

        Map<String, String> props = new LinkedHashMap<>();
        String[] lines = output.split("\\R");
        for (String rawLine : lines) {
            if (rawLine == null) {
                continue;
            }
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            // Example: "17432:" (pid header)
            if (line.endsWith(":") && line.substring(0, line.length() - 1).chars().allMatch(Character::isDigit)) {
                continue;
            }
            // Example: "#Mon Feb 09 12:38:52 CET 2026"
            if (line.startsWith("#")) {
                continue;
            }

            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (!key.isEmpty()) {
                props.put(key, value);
            }
        }
        return props;
    }

    /**
     * Configuration for table parsing.
     */
    public static class TableParserConfig {
        private final String delimiter;
        private final boolean hasRowNumbers;
        private final boolean hasHeaders;
        private final boolean autoDetectSeparator;
        private final boolean parseNumericValues;
        private final int minWhitespaceColumns;

        private TableParserConfig(Builder builder) {
            this.delimiter = builder.delimiter;
            this.hasRowNumbers = builder.hasRowNumbers;
            this.hasHeaders = builder.hasHeaders;
            this.autoDetectSeparator = builder.autoDetectSeparator;
            this.parseNumericValues = builder.parseNumericValues;
            this.minWhitespaceColumns = builder.minWhitespaceColumns;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String delimiter = null; // null means whitespace
            private boolean hasRowNumbers = true;
            private boolean hasHeaders = true;
            private boolean autoDetectSeparator = true;
            private boolean parseNumericValues = true;
            private int minWhitespaceColumns = 2;

            /**
             * Set custom delimiter (null for whitespace).
             */
            public Builder delimiter(String delimiter) {
                this.delimiter = delimiter;
                return this;
            }

            /**
             * Whether rows have leading numbers (e.g., "1:", "2:").
             */
            public Builder hasRowNumbers(boolean hasRowNumbers) {
                this.hasRowNumbers = hasRowNumbers;
                return this;
            }

            /**
             * Whether the table has a header row.
             */
            public Builder hasHeaders(boolean hasHeaders) {
                this.hasHeaders = hasHeaders;
                return this;
            }

            /**
             * Auto-detect separator lines (lines with dashes, equals, etc.).
             */
            public Builder autoDetectSeparator(boolean autoDetectSeparator) {
                this.autoDetectSeparator = autoDetectSeparator;
                return this;
            }

            /**
             * Parse numeric values by removing separators (commas, underscores, spaces).
             */
            public Builder parseNumericValues(boolean parseNumericValues) {
                this.parseNumericValues = parseNumericValues;
                return this;
            }

            /**
             * Minimum number of consecutive spaces to be considered a column separator (default: 2).
             * Only applies when delimiter is null (whitespace mode).
             */
            public Builder minWhitespaceColumns(int minWhitespaceColumns) {
                this.minWhitespaceColumns = minWhitespaceColumns;
                return this;
            }

            public TableParserConfig build() {
                return new TableParserConfig(this);
            }
        }
    }

    /**
     * Represents a parsed table from jcmd output.
     */
    public static class Table {
        private final List<String> headers;
        private final List<TableRow> rows;

        public Table(List<String> headers, List<TableRow> rows) {
            this.headers = headers != null ? Collections.unmodifiableList(headers) : Collections.emptyList();
            this.rows = Collections.unmodifiableList(rows);
        }

        public List<String> getHeaders() {
            return headers;
        }

        public List<TableRow> getRows() {
            return rows;
        }

        public int size() {
            return rows.size();
        }

        public boolean isEmpty() {
            return rows.isEmpty();
        }

        public TableRow get(int index) {
            return rows.get(index);
        }

        @Override
        public String toString() {
            return "Table{headers=" + headers + ", rows=" + rows.size() + "}";
        }
    }

    /**
     * Represents a single row in a parsed table.
     */
    public static class TableRow {
        private final List<String> values;
        private final Map<String, Integer> headerIndex;

        public TableRow(List<String> values, Map<String, Integer> headerIndex) {
            this.values = Collections.unmodifiableList(values);
            this.headerIndex = headerIndex;
        }

        /**
         * Get value by column index.
         */
        public String get(int index) {
            return index >= 0 && index < values.size() ? values.get(index) : null;
        }

        /**
         * Get value by column name (requires headers).
         */
        public String get(String columnName) {
            Integer index = headerIndex.get(columnName);
            return index != null ? get(index) : null;
        }

        /**
         * Get value as Long by column index.
         */
        public Long getLong(int index) {
            String value = get(index);
            return parseNumericValue(value);
        }

        /**
         * Get value as Long by column name.
         */
        public Long getLong(String columnName) {
            String value = get(columnName);
            return parseNumericValue(value);
        }

        /**
         * Get value as Integer by column index.
         */
        public Integer getInt(int index) {
            Long value = getLong(index);
            return value != null ? value.intValue() : null;
        }

        /**
         * Get value as Integer by column name.
         */
        public Integer getInt(String columnName) {
            Long value = getLong(columnName);
            return value != null ? value.intValue() : null;
        }

        public List<String> getValues() {
            return values;
        }

        private Long parseNumericValue(String value) {
            if (value == null || value.isEmpty()) {
                return null;
            }
            // Remove common numeric separators: commas, underscores, spaces
            String cleaned = value.replaceAll("[,_ ]", "");
            try {
                return Long.parseLong(cleaned);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        @Override
        public String toString() {
            return "TableRow" + values;
        }
    }

    private static final Pattern SEPARATOR_LINE_PATTERN = Pattern.compile("^[-=_+]+$");
    private static final Pattern ROW_NUMBER_PATTERN = Pattern.compile("^\\d+:");

    /**
     * Parse tabular jcmd output (like GC.class_histogram, VM.classes, etc.).
     * <p>
     * Example usage:
     * <pre>
     * Table table = JcmdOutputParsers.parseTable(output, 
     *     TableParserConfig.builder()
     *         .hasRowNumbers(true)
     *         .hasHeaders(true)
     *         .build());
     * </pre>
     */
    public static Table parseTable(@Nullable String output, TableParserConfig config) {
        if (output == null || output.isBlank()) {
            return new Table(null, Collections.emptyList());
        }

        String[] lines = output.split("\\R");
        List<String> dataLines = new ArrayList<>();
        
        for (String rawLine : lines) {
            if (rawLine == null) {
                continue;
            }
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            // Skip PID header (e.g., "17432:")
            if (line.endsWith(":") && line.substring(0, line.length() - 1).chars().allMatch(Character::isDigit)) {
                continue;
            }
            // Skip timestamp comments (e.g., "#Mon Feb 09 12:38:52 CET 2026")
            if (line.startsWith("#")) {
                continue;
            }
            // Skip separator lines if auto-detect is enabled
            if (config.autoDetectSeparator && SEPARATOR_LINE_PATTERN.matcher(line).matches()) {
                continue;
            }
            dataLines.add(line);
        }

        if (dataLines.isEmpty()) {
            return new Table(null, Collections.emptyList());
        }

        List<String> headers = null;
        Map<String, Integer> headerIndex = new HashMap<>();
        int startRow = 0;

        // Parse headers if present
        if (config.hasHeaders) {
            String headerLine = dataLines.get(0);
            headers = splitLine(headerLine, config);
            for (int i = 0; i < headers.size(); i++) {
                headerIndex.put(headers.get(i), i);
            }
            startRow = 1;
        }

        // Parse data rows
        List<TableRow> rows = new ArrayList<>();
        for (int i = startRow; i < dataLines.size(); i++) {
            String line = dataLines.get(i);
            List<String> values = splitLine(line, config);
            if (!values.isEmpty()) {
                rows.add(new TableRow(values, headerIndex));
            }
        }

        return new Table(headers, rows);
    }

    /**
     * Parse tabular jcmd output with default configuration.
     */
    public static Table parseTable(@Nullable String output) {
        return parseTable(output, TableParserConfig.builder().build());
    }

    private static List<String> splitLine(String line, TableParserConfig config) {
        List<String> parts = new ArrayList<>();
        
        // Extract and add row number if present
        String processedLine = line.trim();
        if (config.hasRowNumbers && ROW_NUMBER_PATTERN.matcher(processedLine).find()) {
            int colonIndex = processedLine.indexOf(':');
            if (colonIndex >= 0) {
                String rowNum = processedLine.substring(0, colonIndex).trim();
                parts.add(rowNum);
                if (colonIndex < processedLine.length() - 1) {
                    processedLine = processedLine.substring(colonIndex + 1).trim();
                } else {
                    processedLine = "";
                }
            }
        }

        if (processedLine.isEmpty()) {
            return parts;
        }

        // Split by delimiter
        List<String> remainingParts;
        if (config.delimiter != null) {
            remainingParts = Arrays.asList(processedLine.split(Pattern.quote(config.delimiter)));
        } else {
            // Split by multiple consecutive spaces (preserves spaces within column values)
            String regex = "\\s{" + config.minWhitespaceColumns + ",}";
            remainingParts = Arrays.stream(processedLine.split(regex))
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }

        // Trim and add remaining parts
        remainingParts.stream()
                .map(String::trim)
                .forEach(parts::add);

        return parts;
    }
}