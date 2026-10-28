package me.bechberger.jstall.analyzer.impl.vmvitals;

import java.util.List;

/**
 * A section of VM vitals (e.g., "Last 60 minutes" or "Samples at extremes").
 */
public record VmVitalsSection(
    String name,
    List<String> headerLines,
    List<String> columnNames,
    List<DataRow> dataRows,
    List<String> rawDataLines
) {
    public VmVitalsSection {
        headerLines = List.copyOf(headerLines);
        columnNames = List.copyOf(columnNames);
        dataRows = List.copyOf(dataRows);
        rawDataLines = List.copyOf(rawDataLines);
    }

    /**
     * Returns up to N rows for display, oldest first (newest-last).
     * Rows are stored newest-first as they appear in VM.vitals output.
     * If N < 0, returns all rows in oldest-first order.
     */
    public List<DataRow> getTopRows(int topN) {
        List<DataRow> source = dataRows;
        if (topN >= 0 && topN < source.size()) {
            source = source.subList(0, topN);
        }
        // Reverse: newest-first stored → oldest-first for display
        List<DataRow> result = new java.util.ArrayList<>(source);
        java.util.Collections.reverse(result);
        return result;
    }

    /**
     * Returns up to N raw data lines for display, oldest first (newest-last).
     */
    public List<String> getTopRawLines(int topN) {
        List<String> source = rawDataLines;
        if (topN >= 0 && topN < source.size()) {
            source = source.subList(0, topN);
        }
        List<String> result = new java.util.ArrayList<>(source);
        java.util.Collections.reverse(result);
        return result;
    }
}

