package me.bechberger.jstall.cli.live;

import me.bechberger.jstall.analyzer.Cell;
import me.bechberger.jstall.analyzer.TableModel;
import me.bechberger.jstall.analyzer.TableModel.Alignment;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Provides sorting, filtering, and viewport extraction for table data.
 * Can be constructed directly from a TableModel (structured) or by parsing a text string (fallback).
 */
public class TableViewModel {

    private final List<String> preambleLines;
    private final List<String> columnHeaders;
    private final Alignment[] alignments;
    private final List<Cell[]> rows;
    private final int[] columnWidths; // computed column widths for rendering
    private final boolean textMode; // true = plain text lines, no header/separator

    private int sortColumn = -1;
    private boolean sortAscending = true;
    private final List<SortKey> secondarySorts = new ArrayList<>(); // additional sort keys
    private String filterText = "";
    private int scrollOffset = 0;
    private int horizontalOffset = 0;
    private boolean colorEnabled = false;

    private List<Cell[]> processedRows; // after filter + sort

    /** A sort key: column index + direction. */
    public record SortKey(int column, boolean ascending) {}

    private TableViewModel(List<String> preambleLines, List<String> columnHeaders,
                           Alignment[] alignments, int[] columnWidths, List<Cell[]> rows,
                           boolean textMode) {
        this.preambleLines = preambleLines;
        this.columnHeaders = columnHeaders;
        this.alignments = alignments;
        this.columnWidths = columnWidths;
        this.rows = rows;
        this.textMode = textMode;
        this.processedRows = new ArrayList<>(rows);
    }

    /**
     * Creates a TableViewModel directly from a structured TableModel.
     */
    public static TableViewModel fromModel(TableModel model, List<String> preamble) {
        List<String> headers = new ArrayList<>();
        Alignment[] aligns = new Alignment[model.getColumnCount()];
        for (int i = 0; i < model.getColumnCount(); i++) {
            headers.add(model.getColumns().get(i).header());
            aligns[i] = model.getColumns().get(i).alignment();
        }
        int[] widths = model.calculateColumnWidths();
        return new TableViewModel(preamble, headers, aligns, widths, new ArrayList<>(model.getRows()), false);
    }

    /**
     * Creates a TableViewModel for plain text lines (supports scrolling and filtering but no header/sort).
     */
    public static TableViewModel forLines(List<String> lines) {
        List<Cell[]> rows = new ArrayList<>(lines.size());
        int maxWidth = 0;
        for (String line : lines) {
            rows.add(new Cell[]{Cell.text(line)});
            maxWidth = Math.max(maxWidth, line.length());
        }
        return new TableViewModel(List.of(), List.of(), new Alignment[]{Alignment.LEFT},
                new int[]{maxWidth}, rows, true);
    }

    /**
     * Parses analyzer output into a TableViewModel (fallback for unstructured text).
     * Returns null if no table structure is detected.
     */
    public static TableViewModel parse(String output) {
        if (output == null || output.isBlank()) return null;

        String[] lines = output.split("\n", -1);

        // Find the separator line (all dashes and spaces, at least 3 dashes)
        int separatorIdx = -1;
        for (int i = 1; i < lines.length; i++) {
            if (isSeparatorLine(lines[i])) {
                separatorIdx = i;
                break;
            }
        }
        if (separatorIdx < 1) return null;

        // Everything before the header is preamble
        List<String> preamble = new ArrayList<>();
        for (int i = 0; i < separatorIdx - 1; i++) {
            preamble.add(lines[i]);
        }

        String headerLine = lines[separatorIdx - 1];
        String sepLine = lines[separatorIdx];

        // Parse column boundaries from separator line
        List<int[]> colBounds = parseColumnBounds(sepLine);
        if (colBounds.isEmpty()) return null;

        // Extract column headers
        List<String> headers = new ArrayList<>();
        for (int[] bound : colBounds) {
            String h = safeSubstring(headerLine, bound[0], bound[1]).trim();
            headers.add(h);
        }

        // Column widths from separator dashes
        int[] widths = colBounds.stream().mapToInt(b -> b[1] - b[0]).toArray();

        // Default alignment: LEFT for all parsed columns
        Alignment[] aligns = new Alignment[colBounds.size()];
        Arrays.fill(aligns, Alignment.LEFT);

        // Parse data rows
        List<Cell[]> dataRows = new ArrayList<>();
        for (int i = separatorIdx + 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) continue;
            Cell[] cells = new Cell[colBounds.size()];
            for (int c = 0; c < colBounds.size(); c++) {
                String text = safeSubstring(line, colBounds.get(c)[0], colBounds.get(c)[1]).trim();
                cells[c] = Cell.of(text);
            }
            dataRows.add(cells);
        }

        if (dataRows.isEmpty()) return null;

        return new TableViewModel(preamble, headers, aligns, widths, dataRows, false);
    }

    public List<String> getPreambleLines() {
        return preambleLines;
    }

    public List<String> getColumnHeaders() {
        return columnHeaders;
    }

    public int getColumnCount() {
        return columnHeaders.size();
    }

    public boolean isTextMode() {
        return textMode;
    }

    public int getTotalRowCount() {
        return processedRows.size();
    }

    public int getRawRowCount() {
        return rows.size();
    }

    public int getSortColumn() {
        return sortColumn;
    }

    public boolean isSortAscending() {
        return sortAscending;
    }

    public List<SortKey> getSecondarySorts() {
        return Collections.unmodifiableList(secondarySorts);
    }

    public String getFilterText() {
        return filterText;
    }

    public int getScrollOffset() {
        return scrollOffset;
    }

    /**
     * Sets the primary sort column. If same column, toggles direction. Clears secondary sorts. No-op in text mode.
     */
    public void toggleSort(int column) {
        if (textMode) return;
        if (column < 0 || column >= columnHeaders.size()) return;
        if (sortColumn == column) {
            sortAscending = !sortAscending;
        } else {
            sortColumn = column;
            sortAscending = false; // Default to descending (show highest first)
        }
        secondarySorts.clear();
        reprocess();
    }

    /**
     * Adds a secondary sort column (tiebreaker). If the column is already in the sort chain,
     * toggles its direction. If it's the primary column, does nothing. No-op in text mode.
     */
    public void addSecondarySort(int column) {
        if (textMode) return;
        if (column < 0 || column >= columnHeaders.size()) return;
        if (sortColumn < 0) {
            // No primary sort yet — treat as primary
            toggleSort(column);
            return;
        }
        if (column == sortColumn) return; // Already the primary sort
        // Check if already a secondary sort — toggle direction
        for (int i = 0; i < secondarySorts.size(); i++) {
            if (secondarySorts.get(i).column() == column) {
                SortKey old = secondarySorts.get(i);
                secondarySorts.set(i, new SortKey(column, !old.ascending()));
                reprocess();
                return;
            }
        }
        secondarySorts.add(new SortKey(column, false)); // Default descending
        reprocess();
    }

    /**
     * Sets filter text. Resets scroll to 0 only if filter actually changed.
     */
    public void setFilter(String text) {
        String newFilter = text == null ? "" : text;
        if (newFilter.equals(this.filterText)) return;
        this.filterText = newFilter;
        this.scrollOffset = 0;
        reprocess();
    }

    /**
     * Scrolls by delta rows (positive = down, negative = up).
     */
    public void scroll(int delta) {
        scrollOffset = Math.max(0, Math.min(scrollOffset + delta, Math.max(0, processedRows.size() - 1)));
    }

    /**
     * Sets scroll offset to an absolute value.
     */
    public void setScrollOffset(int offset) {
        scrollOffset = Math.max(0, Math.min(offset, Math.max(0, processedRows.size() - 1)));
    }

    /**
     * Scrolls horizontally by delta columns (positive = right, negative = left).
     */
    public void scrollHorizontal(int delta) {
        horizontalOffset = Math.max(0, horizontalOffset + delta);
    }

    /**
     * Returns current horizontal scroll offset.
     */
    public int getHorizontalOffset() {
        return horizontalOffset;
    }

    /**
     * Returns the computed column widths array.
     */
    public int[] getColumnWidths() {
        return columnWidths;
    }

    /**
     * Sets horizontal scroll offset to an absolute value.
     */
    public void setHorizontalOffset(int offset) {
        horizontalOffset = Math.max(0, offset);
    }

    /**
     * Scrolls to top.
     */
    public void scrollToTop() {
        scrollOffset = 0;
    }

    /**
     * Scrolls to bottom (adjusted by viewport when rendering).
     */
    public void scrollToBottom() {
        scrollOffset = Math.max(0, processedRows.size() - 1);
    }

    /**
     * Returns visible rows for the given viewport size.
     */
    public List<Cell[]> getViewport(int maxRows) {
        if (processedRows.isEmpty()) return List.of();
        int end = Math.min(scrollOffset + maxRows, processedRows.size());
        int start = Math.min(scrollOffset, end);
        return processedRows.subList(start, end);
    }

    /**
     * Re-renders the table lines for a viewport (header + separator + visible rows).
     * In text mode, renders plain lines without header/separator.
     */
    public List<String> renderViewport(int maxRows, int maxCols) {
        List<String> output = new ArrayList<>();

        if (!textMode) {
            // Header
            output.add(applyHorizontalScroll(formatHeaderLine(), maxCols));
            output.add(applyHorizontalScroll(renderSeparator(), maxCols));
        }

        // Data rows
        for (Cell[] row : getViewport(maxRows)) {
            output.add(applyHorizontalScroll(formatRow(row), maxCols));
        }

        return output;
    }

    private String applyHorizontalScroll(String line, int maxCols) {
        if (horizontalOffset > 0) {
            line = skipVisibleChars(line, horizontalOffset);
        }
        return line;
    }

    /**
     * Skips the first n visible characters, preserving ANSI escape sequences.
     */
    private static String skipVisibleChars(String s, int n) {
        StringBuilder result = new StringBuilder();
        int visible = 0;
        int i = 0;
        // Skip first n visible chars but keep track of active ANSI state
        while (i < s.length() && visible < n) {
            if (s.charAt(i) == '\033' && i + 1 < s.length() && s.charAt(i + 1) == '[') {
                int start = i;
                i += 2;
                while (i < s.length() && !Character.isLetter(s.charAt(i))) i++;
                if (i < s.length()) i++;
                // Preserve color sequences that were active before the skip point
                result.append(s, start, i);
            } else {
                visible++;
                i++;
            }
        }
        // Append the rest
        if (i < s.length()) {
            result.append(s, i, s.length());
        }
        return result.toString();
    }

    private String formatHeaderLine() {
        Cell[] headerCells = new Cell[columnHeaders.size()];
        for (int i = 0; i < columnHeaders.size(); i++) {
            headerCells[i] = Cell.text(columnHeaders.get(i));
        }
        return formatRow(headerCells);
    }

    public void setColorEnabled(boolean enabled) {
        this.colorEnabled = enabled;
    }

    public boolean isColorEnabled() {
        return colorEnabled;
    }

    private String formatRow(Cell[] cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length && i < columnWidths.length; i++) {
            if (i > 0) sb.append("  ");
            int width = columnWidths[i];
            String content = cells[i].display();
            if (content.length() > width) {
                content = content.substring(0, width - 3) + "...";
            }
            String formatted = formatCell(content, width, alignments[i]);
            if (colorEnabled && cells[i].color() != null) {
                sb.append(cells[i].color().ansi()).append(formatted).append(Cell.Color.reset());
            } else {
                sb.append(formatted);
            }
        }
        return sb.toString();
    }

    private String renderSeparator() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columnWidths.length; i++) {
            if (i > 0) sb.append("  ");
            sb.append("-".repeat(columnWidths[i]));
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

    private void reprocess() {
        // Apply filter
        List<Cell[]> filtered;
        if (filterText.isEmpty()) {
            filtered = new ArrayList<>(rows);
        } else {
            String lower = filterText.toLowerCase();
            filtered = rows.stream()
                    .filter(row -> {
                        for (Cell cell : row) {
                            if (cell.display().toLowerCase().contains(lower)) return true;
                        }
                        return false;
                    })
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        // Apply sort (primary + secondary keys)
        if (sortColumn >= 0 && sortColumn < columnHeaders.size()) {
            Comparator<Cell[]> cmp = (a, b) -> Cell.compare(a[sortColumn], b[sortColumn]);
            if (!sortAscending) cmp = cmp.reversed();
            for (SortKey sk : secondarySorts) {
                if (sk.column() >= 0 && sk.column() < columnHeaders.size()) {
                    Comparator<Cell[]> secondary = (a, b) -> Cell.compare(a[sk.column()], b[sk.column()]);
                    if (!sk.ascending()) secondary = secondary.reversed();
                    cmp = cmp.thenComparing(secondary);
                }
            }
            filtered.sort(cmp);
        }

        processedRows = filtered;

        // Clamp scroll
        if (scrollOffset >= processedRows.size()) {
            scrollOffset = Math.max(0, processedRows.size() - 1);
        }
    }

    private static boolean isSeparatorLine(String line) {
        if (line.isBlank()) return false;
        for (char c : line.toCharArray()) {
            if (c != '-' && c != ' ') return false;
        }
        return line.contains("---");
    }

    private static List<int[]> parseColumnBounds(String separator) {
        List<int[]> bounds = new ArrayList<>();
        int i = 0;
        while (i < separator.length()) {
            while (i < separator.length() && separator.charAt(i) == ' ') i++;
            if (i >= separator.length()) break;
            int start = i;
            while (i < separator.length() && separator.charAt(i) == '-') i++;
            if (i > start) {
                bounds.add(new int[]{start, i});
            }
        }
        return bounds;
    }

    private static String safeSubstring(String s, int start, int end) {
        if (start >= s.length()) return "";
        return s.substring(start, Math.min(end, s.length()));
    }
}
