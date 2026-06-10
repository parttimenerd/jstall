package me.bechberger.jstall.cli.live;

import me.bechberger.jstall.util.render.AnsiCodes;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Interactive renderer that handles the full screen draw loop.
 * Renders: status bar + preamble + table viewport + help bar.
 * Flicker-free using cursor-home + line-by-line overwrite + clear-to-EOL.
 */
public class InteractiveRenderer {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String BOLD_ON = AnsiCodes.BOLD_ON;
    private static final String BOLD_OFF = AnsiCodes.RESET;
    private static final String INVERSE_ON = AnsiCodes.INVERSE_ON;
    private static final String INVERSE_OFF = AnsiCodes.INVERSE_OFF;

    private final RawTerminal terminal;
    private String statusInfo = "";
    private boolean filterMode = false;
    private String filterInput = "";

    public InteractiveRenderer(RawTerminal terminal) {
        this.terminal = terminal;
    }

    public void setStatusInfo(String info) {
        this.statusInfo = info;
    }

    public boolean isFilterMode() {
        return filterMode;
    }

    public void enterFilterMode() {
        this.filterMode = true;
    }

    public void exitFilterMode() {
        this.filterMode = false;
    }

    public String getFilterInput() {
        return filterInput;
    }

    public void setFilterInput(String text) {
        this.filterInput = text;
    }

    public void appendFilterChar(char ch) {
        this.filterInput += ch;
    }

    public void backspaceFilter() {
        if (!filterInput.isEmpty()) {
            filterInput = filterInput.substring(0, filterInput.length() - 1);
        }
    }

    /**
     * Renders the full screen frame.
     *
     * @param model The table view model (may be null if output has no table)
     * @param rawOutput The raw analyzer output (used when model is null)
     * @param sampleNumber Current sample number
     * @param interval Seconds between samples
     */
    public void render(TableViewModel model, String rawOutput, int sampleNumber, int interval) {
        render(model, rawOutput, sampleNumber, interval, null, 0, null);
    }

    /**
     * Renders the full screen frame with optional tab bar.
     */
    public void render(TableViewModel model, String rawOutput, int sampleNumber, int interval,
                       List<String> tabNames, int activeTab) {
        render(model, rawOutput, sampleNumber, interval, tabNames, activeTab, null);
    }

    /**
     * Renders the full screen frame with optional tab bar and timing info.
     */
    public void render(TableViewModel model, String rawOutput, int sampleNumber, int interval,
                       List<String> tabNames, int activeTab, String timing) {
        int rows = terminal.getRows();
        int cols = terminal.getCols();

        terminal.cursorHome();

        int linesPrinted = 0;

        // Line 1: Status bar (inverse video)
        String time = LocalDateTime.now().format(TIME_FMT);
        String timingStr = timing != null ? " | " + timing : "";
        String statusLine = String.format(" %s | Sample #%d | %ds interval%s | %s",
                time, sampleNumber, interval, timingStr, statusInfo);
        terminal.write(INVERSE_ON);
        terminal.writeLine(pad(statusLine, cols));
        terminal.write(INVERSE_OFF);
        linesPrinted++;

        // Line 2: Tab bar (if tabs are present)
        if (tabNames != null && tabNames.size() > 1) {
            terminal.writeLine(buildTabBar(tabNames, activeTab, cols));
            linesPrinted++;
        }

        // Empty line after status/tabs
        terminal.writeLine("");
        linesPrinted++;

        String scrollPosInfo = null;

        if (model != null) {
            // Preamble lines
            for (String preamble : model.getPreambleLines()) {
                if (linesPrinted >= rows - 2) break; // Reserve 2 lines for footer
                terminal.writeLine(preamble);
                linesPrinted++;
            }

            // Sort indicator info (skip in text mode)
            if (!model.isTextMode()) {
                if (model.getSortColumn() >= 0) {
                    StringBuilder sortInfo = new StringBuilder("Sort: ");
                    sortInfo.append(model.getColumnHeaders().get(model.getSortColumn()))
                            .append(model.isSortAscending() ? " ▲" : " ▼");
                    for (TableViewModel.SortKey sk : model.getSecondarySorts()) {
                        sortInfo.append(", ")
                                .append(model.getColumnHeaders().get(sk.column()))
                                .append(sk.ascending() ? " ▲" : " ▼");
                    }
                    if (!model.getFilterText().isEmpty()) {
                        sortInfo.append(" | Filter: \"").append(model.getFilterText()).append("\"");
                    }
                    terminal.writeLine(sortInfo.toString());
                    linesPrinted++;
                } else if (!model.getFilterText().isEmpty()) {
                    terminal.writeLine("Filter: \"" + model.getFilterText() + "\"");
                    linesPrinted++;
                }
            } else if (!model.getFilterText().isEmpty()) {
                terminal.writeLine("Filter: \"" + model.getFilterText() + "\"");
                linesPrinted++;
            }

            // Table viewport
            int availableRows = rows - linesPrinted - 2; // Reserve 2 for footer
            int headerRows = model.isTextMode() ? 0 : 2; // header + separator
            if (availableRows > headerRows + 1) { // Need at least 1 data row
                List<String> tableLines = model.renderViewport(availableRows - headerRows, cols);
                for (String line : tableLines) {
                    terminal.writeLine(line);
                    linesPrinted++;
                }
            }

            // Calculate scroll position info for footer
            int viewportRows = availableRows - headerRows;
            if (model.getTotalRowCount() > viewportRows) {
                int viewEnd = Math.min(model.getScrollOffset() + viewportRows, model.getTotalRowCount());
                String unit = model.isTextMode() ? "lines" : "rows";
                scrollPosInfo = String.format("[%d-%d of %d %s]",
                        model.getScrollOffset() + 1, viewEnd, model.getTotalRowCount(), unit);
            }
        } else {
            // No table detected - render raw output line by line
            String text = rawOutput != null ? rawOutput : "";
            if (!text.isEmpty()) {
                String[] outputLines = text.split("\n", -1);
                int availableRows = rows - linesPrinted - 2;
                for (int i = 0; i < Math.min(outputLines.length, availableRows); i++) {
                    terminal.writeLine(outputLines[i]);
                    linesPrinted++;
                }
            }
        }

        // Clear remaining lines
        while (linesPrinted < rows - 1) {
            terminal.writeLine("");
            linesPrinted++;
        }

        // Footer: help bar or filter prompt
        terminal.write(INVERSE_ON);
        if (filterMode) {
            String filterPrompt = " Filter: " + filterInput + "█";
            terminal.write(pad(filterPrompt, cols));
        } else {
            String help = " q:quit  ↑↓/jk:scroll  ←→/hl:pan  Tab:tabs  +/-:interval  r:refresh  1-9:sort  s+N:subsort  /:filter";
            if (scrollPosInfo != null && help.length() + 2 + scrollPosInfo.length() <= cols) {
                // Right-align scroll position in footer
                int padding = cols - help.length() - scrollPosInfo.length();
                terminal.write(help + " ".repeat(padding) + scrollPosInfo);
            } else {
                terminal.write(pad(help, cols));
            }
        }
        terminal.write(INVERSE_OFF);

        terminal.flush();
    }

    private String pad(String s, int width) {
        if (s.length() >= width) return s.substring(0, width);
        return s + " ".repeat(width - s.length());
    }

    /**
     * Builds a tab bar that fits within maxWidth, scrolling to keep activeTab visible.
     * Trims tabs on left/right edges, showing ‹/› indicators when tabs are hidden.
     */
    private String buildTabBar(List<String> tabNames, int activeTab, int maxWidth) {
        // Calculate visible width of each tab: " name " + "│"
        int[] tabWidths = new int[tabNames.size()];
        for (int i = 0; i < tabNames.size(); i++) {
            tabWidths[i] = tabNames.get(i).length() + 2; // " name "
            if (i < tabNames.size() - 1) tabWidths[i] += 1; // "│"
        }

        // Try to fit all tabs
        int totalWidth = 1; // leading space
        for (int w : tabWidths) totalWidth += w;

        if (totalWidth <= maxWidth) {
            // Everything fits — render normally
            return renderTabs(tabNames, activeTab, 0, tabNames.size());
        }

        // Need to trim. Keep active tab visible, expand outward.
        int leftIndicator = 2; // "‹ "
        int rightIndicator = 2; // " ›"

        int left = activeTab;
        int right = activeTab;
        int usedWidth = 1 + tabWidths[activeTab]; // leading space + active tab

        // Expand to include neighbors
        while (true) {
            boolean expanded = false;
            if (right + 1 < tabNames.size()) {
                int needed = tabWidths[right + 1] + (left > 0 ? leftIndicator : 0);
                if (usedWidth + needed + (right + 2 < tabNames.size() ? rightIndicator : 0) <= maxWidth) {
                    right++;
                    usedWidth += tabWidths[right];
                    expanded = true;
                }
            }
            if (left - 1 >= 0) {
                int needed = tabWidths[left - 1] + (right < tabNames.size() - 1 ? rightIndicator : 0);
                if (usedWidth + needed + (left - 2 >= 0 ? leftIndicator : 0) <= maxWidth) {
                    left--;
                    usedWidth += tabWidths[left];
                    expanded = true;
                }
            }
            if (!expanded) break;
        }

        StringBuilder sb = new StringBuilder();
        if (left > 0) {
            sb.append("‹ ");
        } else {
            sb.append(" ");
        }
        for (int i = left; i <= right; i++) {
            if (i == activeTab) {
                sb.append(BOLD_ON).append(INVERSE_ON)
                  .append(" ").append(tabNames.get(i)).append(" ")
                  .append(INVERSE_OFF).append(BOLD_OFF);
            } else {
                sb.append(" ").append(tabNames.get(i)).append(" ");
            }
            if (i < right) sb.append("│");
        }
        if (right < tabNames.size() - 1) {
            sb.append(" ›");
        }
        return sb.toString();
    }

    private String renderTabs(List<String> tabNames, int activeTab, int from, int to) {
        StringBuilder sb = new StringBuilder(" ");
        for (int i = from; i < to; i++) {
            if (i == activeTab) {
                sb.append(BOLD_ON).append(INVERSE_ON)
                  .append(" ").append(tabNames.get(i)).append(" ")
                  .append(INVERSE_OFF).append(BOLD_OFF);
            } else {
                sb.append(" ").append(tabNames.get(i)).append(" ");
            }
            if (i < to - 1) sb.append("│");
        }
        return sb.toString();
    }
}
