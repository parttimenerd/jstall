package me.bechberger.jstall.analyzer.impl.vmvitals;

import java.util.List;

/**
 * A section of VM vitals (e.g., "Last 60 minutes" or "Samples at extremes").
 */
public record VmVitalsSection(
    String name,
    List<String> headerLines,
    List<String> columnNames,
    List<DataRow> dataRows
) {
    public VmVitalsSection {
        headerLines = List.copyOf(headerLines);
        columnNames = List.copyOf(columnNames);
        dataRows = List.copyOf(dataRows);
    }

    /**
     * Returns up to N rows for display, oldest first (newest-last).
     * Raw rows are stored newest-first as parsed from the vitals output.
     * If N < 0, returns all rows in oldest-first order.
     */
    public List<DataRow> getTopRows(int topN) {
        List<DataRow> source = dataRows;
        if (topN >= 0 && topN < source.size()) {
            source = source.subList(0, topN);
        }
        // Reverse so oldest is first (newest last) for display
        List<DataRow> result = new java.util.ArrayList<>(source);
        java.util.Collections.reverse(result);
        return result;
    }
}

