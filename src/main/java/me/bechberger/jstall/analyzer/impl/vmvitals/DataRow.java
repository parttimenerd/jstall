package me.bechberger.jstall.analyzer.impl.vmvitals;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Parsed VM.vitals data row with raw values, parsed values, and optional extreme markers.
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

    public String getExtremeMarker(String columnName) {
        return extremeMarkers.get(columnName);
    }

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
                extremeMarkers.put(colName, part.substring(part.length() - 1));
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
