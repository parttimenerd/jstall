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
     * Returns the last N rows, or all rows if N < 0.
     */
    public List<DataRow> getTopRows(int topN) {
        if (topN < 0 || topN >= dataRows.size()) {
            return dataRows;
        }
        return dataRows.subList(dataRows.size() - topN, dataRows.size());
    }
}

