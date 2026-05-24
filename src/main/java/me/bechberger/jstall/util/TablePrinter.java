package me.bechberger.jstall.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple table printing framework with column alignment and max cell size.
 */
public class TablePrinter {

    public enum Alignment {
        LEFT, RIGHT
    }

    private record Column(String header, Alignment alignment) {}

    private final List<Column> columns = new ArrayList<>();
    private final List<String[]> rows = new ArrayList<>();
    private int maxCellWidth = 80;

    public TablePrinter() {}

    /**
     * Adds a column to the table.
     */
    public TablePrinter addColumn(String header, Alignment alignment) {
        columns.add(new Column(header, alignment));
        return this;
    }

    /**
     * Adds a column to the table with default left alignment.
     */
    public TablePrinter addColumn(String header) {
        return addColumn(header, Alignment.LEFT);
    }

    /**
     * Sets the maximum cell width (default: 80).
     */
    public TablePrinter setMaxCellWidth(int maxCellWidth) {
        this.maxCellWidth = maxCellWidth;
        return this;
    }

    /**
     * Adds a row to the table.
     */
    public TablePrinter addRow(String... cells) {
        rows.add(cells);
        return this;
    }

    /**
     * Renders the table as a string.
     */
    public String render() {
        if (columns.isEmpty()) return "";

        int numCols = columns.size();
        int[] widths = new int[numCols];

        // Compute column widths from headers
        for (int i = 0; i < numCols; i++) {
            widths[i] = Math.min(columns.get(i).header().length(), maxCellWidth);
        }

        // Compute column widths from rows
        for (String[] row : rows) {
            for (int i = 0; i < Math.min(row.length, numCols); i++) {
                String cell = row[i] != null ? row[i] : "";
                widths[i] = Math.min(Math.max(widths[i], cell.length()), maxCellWidth);
            }
        }

        StringBuilder sb = new StringBuilder();

        // Header row
        for (int i = 0; i < numCols; i++) {
            if (i > 0) sb.append("  ");
            sb.append(formatCell(columns.get(i).header(), widths[i], columns.get(i).alignment()));
        }
        sb.append('\n');

        // Separator
        for (int i = 0; i < numCols; i++) {
            if (i > 0) sb.append("  ");
            sb.append("-".repeat(widths[i]));
        }
        sb.append('\n');

        // Data rows
        for (String[] row : rows) {
            for (int i = 0; i < numCols; i++) {
                if (i > 0) sb.append("  ");
                String cell = (i < row.length && row[i] != null) ? row[i] : "";
                sb.append(formatCell(cell, widths[i], columns.get(i).alignment()));
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    private String formatCell(String value, int width, Alignment alignment) {
        if (value.length() > width) {
            value = value.substring(0, width);
        }
        if (alignment == Alignment.RIGHT) {
            return String.format("%" + width + "s", value);
        } else {
            return String.format("%-" + width + "s", value);
        }
    }
}
