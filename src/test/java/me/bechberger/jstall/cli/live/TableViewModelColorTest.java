package me.bechberger.jstall.cli.live;

import me.bechberger.jstall.analyzer.Cell;
import me.bechberger.jstall.analyzer.TableModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TableViewModelColorTest {

    private TableViewModel makeColoredModel() {
        TableModel table = TableModel.builder()
            .addColumn("NAME", TableModel.Alignment.LEFT)
            .addColumn("VALUE", TableModel.Alignment.RIGHT)
            .addColumn("STATE", TableModel.Alignment.LEFT)
            .addRow(Cell.text("alpha"), Cell.number("50%", 50, Cell.Color.RED), Cell.text("BLOCKED", Cell.Color.RED))
            .addRow(Cell.text("beta"), Cell.number("10%", 10, Cell.Color.GREEN), Cell.text("RUNNABLE", Cell.Color.GREEN))
            .addRow(Cell.text("gamma"), Cell.number("3%", 3), Cell.text("WAITING", Cell.Color.YELLOW))
            .build();
        return TableViewModel.fromModel(table, List.of());
    }

    @Test
    void colorDisabledByDefault() {
        TableViewModel model = makeColoredModel();
        assertFalse(model.isColorEnabled());
        List<String> output = model.renderViewport(10, 120);
        // Should not contain ANSI escape codes
        for (String line : output) {
            assertFalse(line.contains("\033["), "Line should not contain ANSI escapes: " + line);
        }
    }

    @Test
    void colorEnabled_appliesAnsiCodes() {
        TableViewModel model = makeColoredModel();
        model.setColorEnabled(true);
        List<String> output = model.renderViewport(10, 120);
        // Header line (index 0) should NOT have color (header cells have null color)
        assertFalse(output.get(0).contains("\033[31m"), "Header should not be colored");
        // Data rows should have color codes
        String firstDataRow = output.get(2); // after header + separator
        assertTrue(firstDataRow.contains("\033[31m"), "RED cells should have \\033[31m: " + firstDataRow);
        assertTrue(firstDataRow.contains("\033[0m"), "Colored cells should have reset: " + firstDataRow);
    }

    @Test
    void colorEnabled_nullColorCells_noAnsi() {
        TableViewModel model = makeColoredModel();
        model.setColorEnabled(true);
        List<String> output = model.renderViewport(10, 120);
        // Third data row: "gamma" has no color, "3%" has no color, "WAITING" has YELLOW
        String thirdDataRow = output.get(4); // header + sep + 2 data rows
        assertTrue(thirdDataRow.contains("\033[33m"), "YELLOW cell should be present: " + thirdDataRow);
        // "gamma" should NOT be wrapped in color codes
        int gammaIdx = thirdDataRow.indexOf("gamma");
        assertTrue(gammaIdx >= 0);
        // Check that no ANSI code immediately precedes "gamma"
        if (gammaIdx > 0) {
            assertFalse(thirdDataRow.substring(Math.max(0, gammaIdx - 5), gammaIdx).contains("\033[3"),
                "Name cell without color should not have ANSI prefix");
        }
    }

    @Test
    void colorEnabled_sortPreservesColors() {
        TableViewModel model = makeColoredModel();
        model.setColorEnabled(true);
        model.toggleSort(1); // descending
        model.toggleSort(1); // ascending
        List<String> output = model.renderViewport(10, 120);
        // After sort, "gamma" (3%) should be first data row
        String firstData = output.get(2);
        assertFalse(firstData.contains("\033[31m"), "gamma row should not have RED");
    }

    @Test
    void skipVisibleChars_noAnsi() {
        TableViewModel model = makeColoredModel();
        model.setColorEnabled(false);
        model.setHorizontalOffset(5);
        List<String> output = model.renderViewport(10, 120);
        // With offset 5, first 5 visible chars are skipped
        assertNotNull(output);
        assertFalse(output.isEmpty());
    }

    @Test
    void skipVisibleChars_withAnsi_preservesSequences() {
        TableViewModel model = makeColoredModel();
        model.setColorEnabled(true);
        model.setHorizontalOffset(3);
        List<String> output = model.renderViewport(10, 120);
        // Should not crash, and colored rows should still have reset codes
        for (String line : output) {
            if (line.contains("\033[3")) {
                assertTrue(line.contains("\033[0m"), "Colored lines should still have reset after scroll");
            }
        }
    }

    @Test
    void skipVisibleChars_offsetBeyondContent_returnsAnsiOnly() {
        TableViewModel model = makeColoredModel();
        model.setColorEnabled(true);
        model.setHorizontalOffset(200); // way beyond content
        List<String> output = model.renderViewport(10, 120);
        // Lines should be empty or contain only ANSI sequences
        for (String line : output) {
            String visible = line.replaceAll("\033\\[[0-9;]*[a-zA-Z]", "");
            assertEquals("", visible.trim(), "All visible content should be skipped: '" + line + "'");
        }
    }

    @Test
    void textMode_colorNotApplied() {
        TableViewModel model = TableViewModel.forLines(List.of("line 1", "line 2", "line 3"));
        model.setColorEnabled(true);
        List<String> output = model.renderViewport(10, 120);
        // Text mode cells have null color, so no ANSI should appear
        for (String line : output) {
            assertFalse(line.contains("\033["), "Text mode should not have ANSI: " + line);
        }
    }
}
