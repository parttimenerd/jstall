package me.bechberger.jstall.analyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Structured table model with typed cells and column alignment.
 * Replaces TablePrinter as the canonical table representation.
 */
public class TableModel {

    public enum Alignment { LEFT, RIGHT, CENTER }

    public record Column(String header, Alignment alignment) {
        public Column(String header) {
            this(header, Alignment.LEFT);
        }
    }

    private final List<Column> columns;
    private final List<Cell[]> rows;
    private final int maxCellWidth;

    private TableModel(List<Column> columns, List<Cell[]> rows, int maxCellWidth) {
        this.columns = Collections.unmodifiableList(columns);
        this.rows = Collections.unmodifiableList(rows);
        this.maxCellWidth = maxCellWidth;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<Column> getColumns() {
        return columns;
    }

    public List<Cell[]> getRows() {
        return rows;
    }

    public int getColumnCount() {
        return columns.size();
    }

    public int getRowCount() {
        return rows.size();
    }

    public int getMaxCellWidth() {
        return maxCellWidth;
    }

    /** Renders the table to a formatted string (same format as the old TablePrinter). */
    public String render() {
        if (columns.isEmpty()) return "";

        int[] widths = calculateColumnWidths();
        StringBuilder sb = new StringBuilder();

        // Header
        sb.append(renderRow(getHeaderCells(), widths)).append('\n');
        // Separator
        sb.append(renderSeparator(widths)).append('\n');
        // Data rows
        for (Cell[] row : rows) {
            sb.append(renderRow(row, widths)).append('\n');
        }

        return sb.toString().trim();
    }

    private Cell[] getHeaderCells() {
        Cell[] headers = new Cell[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            headers[i] = Cell.text(columns.get(i).header());
        }
        return headers;
    }

    public int[] calculateColumnWidths() {
        int[] widths = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            widths[i] = Math.min(columns.get(i).header().length(), maxCellWidth);
        }
        for (Cell[] row : rows) {
            for (int i = 0; i < row.length && i < widths.length; i++) {
                int cellWidth = Math.min(row[i].display().length(), maxCellWidth);
                widths[i] = Math.max(widths[i], cellWidth);
            }
        }
        return widths;
    }

    private String renderRow(Cell[] cells, int[] widths) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length && i < widths.length; i++) {
            if (i > 0) sb.append("  ");
            String content = truncate(cells[i].display(), maxCellWidth);
            sb.append(formatCell(content, widths[i], columns.get(i).alignment()));
        }
        return sb.toString();
    }

    private String renderSeparator(int[] widths) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < widths.length; i++) {
            if (i > 0) sb.append("  ");
            sb.append("-".repeat(widths[i]));
        }
        return sb.toString();
    }

    private static String formatCell(String content, int width, Alignment alignment) {
        if (content.length() >= width) return content;
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

    private static String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }

    public static class Builder {
        private final List<Column> columns = new ArrayList<>();
        private final List<Cell[]> rows = new ArrayList<>();
        private int maxCellWidth = 80;

        public Builder addColumn(String header, Alignment alignment) {
            columns.add(new Column(header, alignment));
            return this;
        }

        public Builder addColumn(String header) {
            return addColumn(header, Alignment.LEFT);
        }

        public Builder setMaxCellWidth(int maxCellWidth) {
            this.maxCellWidth = maxCellWidth;
            return this;
        }

        /** Adds a row with typed cells. */
        public Builder addRow(Cell... cells) {
            if (cells.length != columns.size()) {
                throw new IllegalArgumentException(
                        "Row has " + cells.length + " cells but table has " + columns.size() + " columns");
            }
            rows.add(cells.clone());
            return this;
        }

        /** Convenience: adds a row from display strings, auto-detecting sort values. */
        public Builder addRow(String... cells) {
            if (cells.length != columns.size()) {
                throw new IllegalArgumentException(
                        "Row has " + cells.length + " cells but table has " + columns.size() + " columns");
            }
            Cell[] cellArray = new Cell[cells.length];
            for (int i = 0; i < cells.length; i++) {
                cellArray[i] = Cell.of(cells[i]);
            }
            rows.add(cellArray);
            return this;
        }

        public TableModel build() {
            return new TableModel(new ArrayList<>(columns), new ArrayList<>(rows), maxCellWidth);
        }
    }
}
