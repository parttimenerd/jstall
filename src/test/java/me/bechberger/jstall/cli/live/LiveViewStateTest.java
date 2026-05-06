package me.bechberger.jstall.cli.live;

import me.bechberger.jstall.analyzer.AnalyzerOutput;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.Cell;
import me.bechberger.jstall.analyzer.TableModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LiveViewStateTest {

    private static AnalyzerOutput.CompositeOutput compositeV1() {
        TableModel threadsTable = TableModel.builder()
            .addColumn("THREAD", TableModel.Alignment.LEFT)
            .addColumn("CPU", TableModel.Alignment.RIGHT)
            .addRow(Cell.text("t-1"), Cell.number("10", 10))
            .addRow(Cell.text("t-2"), Cell.number("20", 20))
            .addRow(Cell.text("t-3"), Cell.number("30", 30))
            .build();

        TableModel locksTable = TableModel.builder()
            .addColumn("LOCK", TableModel.Alignment.LEFT)
            .addColumn("WAITERS", TableModel.Alignment.RIGHT)
            .addRow(Cell.text("L-1"), Cell.number("2", 2))
            .addRow(Cell.text("L-2"), Cell.number("1", 1))
            .build();

        return new AnalyzerOutput.CompositeOutput(List.of(
            new AnalyzerOutput.CompositeOutput.Section("threads",
                new AnalyzerOutput.TableOutput(List.of(), threadsTable)),
            new AnalyzerOutput.CompositeOutput.Section("locks",
                new AnalyzerOutput.TableOutput(List.of(), locksTable))
        ));
    }

    private static AnalyzerOutput.CompositeOutput compositeV2() {
        TableModel threadsTable = TableModel.builder()
            .addColumn("THREAD", TableModel.Alignment.LEFT)
            .addColumn("CPU", TableModel.Alignment.RIGHT)
            .addRow(Cell.text("t-1"), Cell.number("11", 11))
            .addRow(Cell.text("t-2"), Cell.number("22", 22))
            .addRow(Cell.text("t-3"), Cell.number("33", 33))
            .addRow(Cell.text("t-4"), Cell.number("44", 44))
            .build();

        TableModel locksTable = TableModel.builder()
            .addColumn("LOCK", TableModel.Alignment.LEFT)
            .addColumn("WAITERS", TableModel.Alignment.RIGHT)
            .addRow(Cell.text("L-1"), Cell.number("3", 3))
            .addRow(Cell.text("L-2"), Cell.number("2", 2))
            .build();

        return new AnalyzerOutput.CompositeOutput(List.of(
            new AnalyzerOutput.CompositeOutput.Section("threads",
                new AnalyzerOutput.TableOutput(List.of(), threadsTable)),
            new AnalyzerOutput.CompositeOutput.Section("locks",
                new AnalyzerOutput.TableOutput(List.of(), locksTable))
        ));
    }

    @Test
    void tabNavigation_wrapsAndKeepsNames() {
        LiveViewState state = LiveViewState.fromStructured(compositeV1());

        assertEquals(List.of("All", "threads", "locks"), state.tabNames());
        assertEquals(0, state.activeTab());

        state = state.withNextTab();
        assertEquals(1, state.activeTab());
        assertTrue(state.displayOutput().contains("THREAD"));

        state = state.withNextTab();
        assertEquals(2, state.activeTab());
        assertTrue(state.displayOutput().contains("LOCK"));

        state = state.withNextTab();
        assertEquals(0, state.activeTab());

        state = state.withPreviousTab();
        assertEquals(2, state.activeTab());
    }

    @Test
    void refreshFromResult_preservesModelViewState() {
        LiveViewState state = LiveViewState.fromStructured(compositeV1()).withNextTab(); // threads tab
        assertNotNull(state.model());

        state.model().toggleSort(1);
        state.model().setFilter("t-");
        state.model().scroll(1);
        state.model().scrollHorizontal(6);

        AnalyzerResult resultV2 = AnalyzerResult.ok(compositeV2());
        LiveViewState refreshed = state.refreshFromResult(resultV2);

        assertEquals(1, refreshed.activeTab());
        assertNotNull(refreshed.model());
        assertEquals(1, refreshed.model().getSortColumn());
        assertEquals("t-", refreshed.model().getFilterText());
        assertEquals(1, refreshed.model().getScrollOffset());
        assertEquals(6, refreshed.model().getHorizontalOffset());
    }
}
