package me.bechberger.jstall.cli.live;

import me.bechberger.jstall.analyzer.Cell;
import me.bechberger.jstall.analyzer.TableModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TableViewModelTest {

    private static final String TABLE = String.join("\n",
            "THREAD  CPU",
            "------  ---",
            "t-1     10",
            "t-2     20",
            "t-3     30",
            "t-4     40");

    @Test
    void setFilter_doesNotResetScroll_whenFilterUnchanged() {
        TableViewModel model = TableViewModel.parse(TABLE);
        assertNotNull(model);

        model.scroll(2);
        assertEquals(2, model.getScrollOffset());

        model.setFilter("");
        assertEquals(2, model.getScrollOffset());
    }

    @Test
    void horizontalScroll_isClampedToZero() {
        TableViewModel model = TableViewModel.parse(TABLE);
        assertNotNull(model);

        model.scrollHorizontal(8);
        assertEquals(8, model.getHorizontalOffset());

        model.scrollHorizontal(-100);
        assertEquals(0, model.getHorizontalOffset());
    }

    @Test
    void colorEnabled_rendersAnsiCodes() {
        TableModel tableModel = TableModel.builder()
            .addColumn("NAME", TableModel.Alignment.LEFT)
            .addColumn("STATE", TableModel.Alignment.LEFT)
            .addRow(Cell.text("thread-1"), Cell.text("RUNNABLE", Cell.Color.GREEN))
            .addRow(Cell.text("thread-2"), Cell.text("BLOCKED", Cell.Color.RED))
            .build();

        TableViewModel model = TableViewModel.fromModel(tableModel, List.of());
        model.setColorEnabled(true);

        List<String> lines = model.renderViewport(10, 200);
        // lines[0] = header, lines[1] = separator, lines[2+] = data
        assertTrue(lines.size() >= 4);

        // Data rows should contain ANSI color codes
        String row1 = lines.get(2);
        assertTrue(row1.contains("\033[32m"), "RUNNABLE row should contain green ANSI code");
        assertTrue(row1.contains("\033[0m"), "RUNNABLE row should contain reset code");

        String row2 = lines.get(3);
        assertTrue(row2.contains("\033[31m"), "BLOCKED row should contain red ANSI code");
    }

    @Test
    void colorDisabled_noAnsiCodes() {
        TableModel tableModel = TableModel.builder()
            .addColumn("NAME", TableModel.Alignment.LEFT)
            .addColumn("STATE", TableModel.Alignment.LEFT)
            .addRow(Cell.text("thread-1"), Cell.text("RUNNABLE", Cell.Color.GREEN))
            .build();

        TableViewModel model = TableViewModel.fromModel(tableModel, List.of());
        // colorEnabled is false by default

        List<String> lines = model.renderViewport(10, 200);
        String row1 = lines.get(2);
        assertFalse(row1.contains("\033["), "No ANSI codes when color is disabled");
    }

    @Test
    void colorEnabled_nullColorCells_noAnsiCodes() {
        TableModel tableModel = TableModel.builder()
            .addColumn("NAME", TableModel.Alignment.LEFT)
            .addRow(Cell.text("plain-text"))
            .build();

        TableViewModel model = TableViewModel.fromModel(tableModel, List.of());
        model.setColorEnabled(true);

        List<String> lines = model.renderViewport(10, 200);
        String row1 = lines.get(2);
        assertFalse(row1.contains("\033["), "No ANSI codes for cells without color");
    }

    @Test
    void horizontalScroll_preservesAnsiSequences() {
        TableModel tableModel = TableModel.builder()
            .addColumn("NAME", TableModel.Alignment.LEFT)
            .addColumn("VALUE", TableModel.Alignment.LEFT)
            .addRow(Cell.text("abcdef", Cell.Color.RED), Cell.text("xyz"))
            .build();

        TableViewModel model = TableViewModel.fromModel(tableModel, List.of());
        model.setColorEnabled(true);
        model.scrollHorizontal(3);

        List<String> lines = model.renderViewport(10, 200);
        String row = lines.get(2);
        // After scrolling 3 visible chars, the ANSI code should still be present
        assertTrue(row.contains("\033[31m"), "ANSI code should survive horizontal scroll");
    }

    @Test
    void horizontalScroll_beyondContent_producesEmptyish() {
        TableViewModel model = TableViewModel.parse(TABLE);
        assertNotNull(model);
        model.scrollHorizontal(1000);

        List<String> lines = model.renderViewport(10, 200);
        // All lines should be empty or very short after scrolling past content
        for (String line : lines) {
            assertEquals(0, line.trim().length(), "Lines should be empty after scrolling past content");
        }
    }
}
