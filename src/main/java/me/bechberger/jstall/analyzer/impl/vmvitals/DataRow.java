package me.bechberger.jstall.analyzer.impl.vmvitals;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Parsed VM.vitals data row with raw values, parsed values, and optional extreme markers.
 * extremeMarkers is keyed by positional index (as String) to handle duplicate column names.
 */
public record DataRow(
    LocalDateTime timestamp,
    Map<String, String> values,
    Map<String, ParsedValue> parsedValues,
    Map<String, String> extremeMarkers,
    List<String> valueList
) {
    public static final DateTimeFormatter TIMESTAMP_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public DataRow {
        values = Map.copyOf(values);
        parsedValues = Map.copyOf(parsedValues);
        extremeMarkers = Map.copyOf(extremeMarkers);
        valueList = List.copyOf(valueList);
    }

    public ParsedValue getParsedValue(String columnName) {
        return parsedValues.get(columnName);
    }

    /** Returns the extreme marker ('+' or '-') for the column at the given positional index, or null. */
    public String getExtremeMarker(int columnIndex) {
        return extremeMarkers.get(String.valueOf(columnIndex));
    }

    /** @deprecated Use {@link #getExtremeMarker(int)} with positional index instead. */
    @Deprecated
    public String getExtremeMarker(String columnName) {
        return extremeMarkers.get(columnName);
    }

    public boolean isExtreme(int columnIndex) {
        return extremeMarkers.containsKey(String.valueOf(columnIndex));
    }

    /** @deprecated Use {@link #isExtreme(int)} with positional index instead. */
    @Deprecated
    public boolean isExtreme(String columnName) {
        return extremeMarkers.containsKey(columnName);
    }

    public static DataRow parse(String line, List<String> columnNames) {
        String trimmed = line.trim();
        LocalDateTime timestamp = parseTimestamp(trimmed);
        if (timestamp == null) {
            return null;
        }
        return fromTokens(timestamp, Arrays.asList(trimmed.substring(19).trim().split("\\s+")), columnNames);
    }

    public static DataRow parseFixedWidth(String line,
                                          List<String> columnNames,
                                          List<Integer> columnStarts,
                                          int dataStartIndex) {
        String trimmed = line.trim();
        LocalDateTime timestamp = parseTimestamp(trimmed);
        if (timestamp == null) {
            return null;
        }

        List<String> values = new ArrayList<>();
        for (int i = 0; i < columnStarts.size(); i++) {
            int start = dataStartIndex + columnStarts.get(i);
            int end = i + 1 < columnStarts.size()
                ? dataStartIndex + columnStarts.get(i + 1)
                : line.length();
            if (start >= line.length()) {
                values.add("");
                continue;
            }
            values.add(line.substring(start, Math.min(end, line.length())).trim());
        }

        return fromTokens(timestamp, values, columnNames);
    }

    /** Parses a fixed-width data row using absolute column-start positions in the line. */
    public static DataRow parseFixedWidthAbsolute(String line,
                                                   List<String> columnNames,
                                                   List<Integer> columnStarts) {
        String trimmed = line.trim();
        LocalDateTime timestamp = parseTimestamp(trimmed);
        if (timestamp == null) {
            return null;
        }

        List<String> values = new ArrayList<>();
        for (int i = 0; i < columnStarts.size(); i++) {
            int start = columnStarts.get(i);
            int end = i + 1 < columnStarts.size() ? columnStarts.get(i + 1) : line.length();
            if (start >= line.length()) {
                values.add("");
                continue;
            }
            values.add(line.substring(start, Math.min(end, line.length())).trim());
        }

        return fromTokens(timestamp, values, columnNames);
    }

    /**
     * Parses a fixed-width data row where columnStarts are relative to the column-header's
     * data-portion start (colHeaderDataStart). The method finds the actual data start in the
     * data line (position after timestamp + any whitespace) to align relative offsets correctly.
     */
    public static DataRow parseFixedWidthRelative(String line,
                                                   List<String> columnNames,
                                                   List<Integer> columnStarts,
                                                   int colHeaderDataStart) {
        String trimmed = line.trim();
        LocalDateTime timestamp = parseTimestamp(trimmed);
        if (timestamp == null) {
            return null;
        }

        // Find where data actually starts in the data line: skip past the 19-char timestamp
        // then skip any whitespace separating timestamp from data columns.
        int dataStart = TIMESTAMP_FORMATTER.format(java.time.LocalDateTime.now()).length();
        while (dataStart < trimmed.length() && trimmed.charAt(dataStart) == ' ') dataStart++;

        // Map relative-to-header offsets to absolute positions in the trimmed data line
        // Offset 0 in the header corresponds to dataStart in the data line.
        // The header's colHeaderDataStart is the absolute start of its data portion.
        // So data[dataStart + relOffset] = header[colHeaderDataStart + relOffset].
        List<String> values = new ArrayList<>();
        for (int i = 0; i < columnStarts.size(); i++) {
            int start = dataStart + columnStarts.get(i);
            int end = i + 1 < columnStarts.size()
                ? dataStart + columnStarts.get(i + 1)
                : trimmed.length();
            if (start >= trimmed.length()) {
                values.add("");
                continue;
            }
            values.add(trimmed.substring(start, Math.min(end, trimmed.length())).trim());
        }

        return fromTokens(timestamp, values, columnNames);
    }

    static DataRow fromTimestampAndValues(String timestampStr, List<String> values, List<String> columnNames) {
        try {
            return fromTokens(LocalDateTime.parse(timestampStr, TIMESTAMP_FORMATTER), values, columnNames);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDateTime parseTimestamp(String trimmed) {
        if (trimmed.length() < 19) {
            return null;
        }
        try {
            return LocalDateTime.parse(trimmed.substring(0, 19), TIMESTAMP_FORMATTER);
        } catch (Exception e) {
            return null;
        }
    }

    private static DataRow fromTokens(LocalDateTime timestamp, List<String> parts, List<String> columnNames) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, ParsedValue> parsedValues = new LinkedHashMap<>();
        Map<String, String> extremeMarkers = new LinkedHashMap<>();
        List<String> valueList = new ArrayList<>();

        for (int i = 0; i < Math.min(columnNames.size(), parts.size()); i++) {
            String part = parts.get(i);
            String colName = columnNames.get(i);
            String actualValue = part;
            if (part.endsWith("+") || part.endsWith("-")) {
                extremeMarkers.put(String.valueOf(i), part.substring(part.length() - 1));
                actualValue = part.substring(0, part.length() - 1);
            }

            values.put(colName, actualValue);
            parsedValues.put(colName, ParsedValue.parse(actualValue));
            valueList.add(actualValue);
        }

        // Pad valueList to match columnNames length if parts was shorter
        while (valueList.size() < columnNames.size()) {
            valueList.add("");
        }

        return new DataRow(timestamp, values, parsedValues, extremeMarkers, valueList);
    }

    /** Returns a copy of this row with additional extreme markers merged in from another row. */
    public DataRow withMergedMarkers(DataRow other) {
        Map<String, String> merged = new LinkedHashMap<>(extremeMarkers);
        merged.putAll(other.extremeMarkers);
        return new DataRow(timestamp, values, parsedValues, merged, valueList);
    }

    public Map<String, ParsedValue> computeDeltas(DataRow previous) {
        if (previous == null) {
            return Map.of();
        }

        Map<String, ParsedValue> deltas = new LinkedHashMap<>();
        for (String col : values.keySet()) {
            ParsedValue current = getParsedValue(col);
            ParsedValue prev = previous.getParsedValue(col);

            if (current != null && prev != null && current.isAvailable() && prev.isAvailable()) {
                long delta = current.getBytes() - prev.getBytes();
                if (delta >= 0) {
                    deltas.put(col, ParsedValue.parse(String.valueOf(delta)));
                }
            }
        }
        return deltas;
    }
}
