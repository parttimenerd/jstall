package me.bechberger.jstall.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Simple table printing framework with column alignment and max cell size.
 */
public class TablePrinter {

    private final List<Column> columns = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private int maxCellWidth = 80;

    /**
     * Adds a column to the table.
     *
     * @param header The column header
     * @param alignment The column alignment
     * @return this TablePrinter for chaining
     */
    public TablePrinter addColumn(String header, Alignment alignment) {
        columns.add(new Column(header, alignment));
        return this;
    }

    /**
     * Adds a column to the table with default left alignment.
     *
     * @param header The column header
     * @return this TablePrinter for chaining
     */
    public TablePrinter addColumn(String header) {
        return addColumn(header, Alignment.LEFT);
    }

    /**
     * Sets the maximum cell width (default: 80).
     *
     * @param maxCellWidth Maximum width for any cell
     * @return this TablePrinter for chaining
     */
    public TablePrinter setMaxCellWidth(int maxCellWidth) {
        this.maxCellWidth = maxCellWidth;
        return this;
    }

    /**
     * Adds a row to the table.
     *
     * @param cells The cell values (must match the number of columns)
     * @return this TablePrinter for chaining
     */
    public TablePrinter addRow(String... cells) {
        if (cells.length != columns.size()) {
            throw new IllegalArgumentException(
                "Row has " + cells.length + " cells but table has " + columns.size() + " columns");
        }
        rows.add(new Row(cells));
        return this;
    }

    /**
     * Renders the table as a string.
     *
     * @return The formatted table
     */
    public String render() {
        if (columns.isEmpty()) {
            return "";
        }

        // Calculate column widths
        int[] widths = calculateColumnWidths();

        StringBuilder sb = new StringBuilder();

        // Print header
        sb.append(renderRow(getHeaderRow(), widths)).append("\n");

        // Print separator
        sb.append(renderSeparator(widths)).append("\n");

        // Print rows
        for (Row row : rows) {
            sb.append(renderRow(row.cells, widths)).append("\n");
        }

        return sb.toString().trim();
    }

    private String[] getHeaderRow() {
        return columns.stream().map(c -> c.header).toArray(String[]::new);
    }

    private int[] calculateColumnWidths() {
        int[] widths = new int[columns.size()];

        // Start with header widths
        for (int i = 0; i < columns.size(); i++) {
            widths[i] = Math.min(columns.get(i).header.length(), maxCellWidth);
        }

        // Consider all row data
        for (Row row : rows) {
            for (int i = 0; i < row.cells.length; i++) {
                int cellWidth = Math.min(row.cells[i].length(), maxCellWidth);
                widths[i] = Math.max(widths[i], cellWidth);
            }
        }

        return widths;
    }

    private String renderRow(String[] cells, int[] widths) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append("  ");
            }

            String cell = truncate(cells[i], maxCellWidth);
            String formatted = formatCell(cell, widths[i], columns.get(i).alignment);
            sb.append(formatted);
        }

        return sb.toString();
    }

    private String renderSeparator(int[] widths) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < widths.length; i++) {
            if (i > 0) {
                sb.append("  ");
            }
            sb.append("-".repeat(widths[i]));
        }

        return sb.toString();
    }

    private String formatCell(String content, int width, Alignment alignment) {
        if (content.length() >= width) {
            return content;
        }

        int padding = width - content.length();

        return switch (alignment) {
            case LEFT -> content + " ".repeat(padding);
            case RIGHT -> " ".repeat(padding) + content;
            case CENTER -> {
                int leftPad = padding / 2;
                int rightPad = padding - leftPad;
                yield " ".repeat(leftPad) + content + " ".repeat(rightPad);
            }
        };
    }

    private String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen - 3) + "...";
    }

    public enum Alignment {
        LEFT, RIGHT, CENTER
    }

    private static class Column {
        final String header;
        final Alignment alignment;

        Column(String header, Alignment alignment) {
            this.header = header;
            this.alignment = alignment;
        }
    }

    private static class Row {
        final String[] cells;

        Row(String[] cells) {
            this.cells = cells;
        }
    }
}