package me.bechberger.jstall.cli.live;

import me.bechberger.jstall.analyzer.Cell;
import me.bechberger.jstall.analyzer.TableModel;
import me.bechberger.jstall.analyzer.AnalyzerOutput;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for live mode key handling logic, interactive renderer state,
 * and live view state transitions.
 */
class LiveModeKeyHandlingTest {

    private static TableModel sampleTable() {
        return TableModel.builder()
                .addColumn("THREAD", TableModel.Alignment.LEFT)
                .addColumn("CPU TIME", TableModel.Alignment.RIGHT)
                .addColumn("CPU%", TableModel.Alignment.RIGHT)
                .addColumn("STATE", TableModel.Alignment.LEFT)
                .addRow(Cell.text("main"), Cell.number("5.20s", 5.2), Cell.number("45%", 45), Cell.text("RUNNABLE", Cell.Color.GREEN))
                .addRow(Cell.text("worker-1"), Cell.number("3.10s", 3.1), Cell.number("28%", 28), Cell.text("RUNNABLE", Cell.Color.GREEN))
                .addRow(Cell.text("gc-thread"), Cell.number("1.50s", 1.5), Cell.number("12%", 12), Cell.text("WAITING", Cell.Color.YELLOW))
                .addRow(Cell.text("http-nio"), Cell.number("0.80s", 0.8), Cell.number("7%", 7), Cell.text("BLOCKED", Cell.Color.RED))
                .addRow(Cell.text("idle-pool"), Cell.number("0.01s", 0.01), Cell.number("0%", 0), Cell.text("WAITING", Cell.Color.YELLOW))
                .build();
    }

    private static AnalyzerOutput.CompositeOutput sampleComposite() {
        TableModel threadsTable = sampleTable();
        TableModel locksTable = TableModel.builder()
                .addColumn("LOCK", TableModel.Alignment.LEFT)
                .addColumn("OWNER", TableModel.Alignment.LEFT)
                .addColumn("WAITERS", TableModel.Alignment.RIGHT)
                .addRow(Cell.text("<0xAAA>"), Cell.text("main"), Cell.number("2", 2))
                .addRow(Cell.text("<0xBBB>"), Cell.text("worker-1"), Cell.number("1", 1))
                .build();

        return new AnalyzerOutput.CompositeOutput(List.of(
                new AnalyzerOutput.CompositeOutput.Section("threads",
                        new AnalyzerOutput.TableOutput(List.of(), threadsTable)),
                new AnalyzerOutput.CompositeOutput.Section("locks",
                        new AnalyzerOutput.TableOutput(List.of(), locksTable))
        ));
    }

    @Nested
    class SortingTests {

        @Test
        void toggleSort_sortsByColumnDescendingFirst() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.toggleSort(1); // Sort by CPU TIME descending

            assertEquals(1, model.getSortColumn());
            assertFalse(model.isSortAscending());

            List<Cell[]> viewport = model.getViewport(10);
            // First row should have highest CPU time (main: 5.2s)
            assertEquals("5.20s", viewport.get(0)[1].display());
            assertEquals("3.10s", viewport.get(1)[1].display());
        }

        @Test
        void toggleSort_sameColumnTogglesDirection() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.toggleSort(1); // descending
            assertFalse(model.isSortAscending());

            model.toggleSort(1); // ascending
            assertTrue(model.isSortAscending());

            List<Cell[]> viewport = model.getViewport(10);
            // First row should have lowest CPU time (idle-pool: 0.01s)
            assertEquals("0.01s", viewport.get(0)[1].display());
        }

        @Test
        void toggleSort_differentColumnResetsDirection() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.toggleSort(1); // CPU TIME descending
            model.toggleSort(1); // CPU TIME ascending

            model.toggleSort(2); // CPU% — should reset to descending
            assertFalse(model.isSortAscending());
            assertEquals(2, model.getSortColumn());
        }

        @Test
        void secondarySort_addsBreaker() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.toggleSort(3); // Sort by STATE
            model.addSecondarySort(1); // Then by CPU TIME

            List<TableViewModel.SortKey> secondary = model.getSecondarySorts();
            assertEquals(1, secondary.size());
            assertEquals(1, secondary.get(0).column());
            assertFalse(secondary.get(0).ascending()); // default descending
        }

        @Test
        void secondarySort_togglesDirectionIfAlreadyPresent() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.toggleSort(3); // primary
            model.addSecondarySort(1); // secondary descending
            assertFalse(model.getSecondarySorts().get(0).ascending());

            model.addSecondarySort(1); // toggle to ascending
            assertTrue(model.getSecondarySorts().get(0).ascending());
        }

        @Test
        void secondarySort_ignoredIfSameAsPrimary() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.toggleSort(2); // primary on CPU%
            model.addSecondarySort(2); // same column — ignored

            assertTrue(model.getSecondarySorts().isEmpty());
        }

        @Test
        void toggleSort_clearsSecondarySorts() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.toggleSort(3); // primary
            model.addSecondarySort(1); // secondary
            assertFalse(model.getSecondarySorts().isEmpty());

            model.toggleSort(2); // new primary — clears secondary
            assertTrue(model.getSecondarySorts().isEmpty());
        }

        @Test
        void sortByColumn_outOfBounds_ignored() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.toggleSort(99); // out of bounds
            assertEquals(-1, model.getSortColumn());
        }

        @Test
        void sort_textMode_ignored() {
            TableViewModel model = TableViewModel.forLines(List.of("line 1", "line 2"));
            model.toggleSort(0);
            assertEquals(-1, model.getSortColumn()); // no-op in text mode
        }
    }

    @Nested
    class FilteringTests {

        @Test
        void filter_reducesVisibleRows() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            assertEquals(5, model.getTotalRowCount());

            model.setFilter("worker");
            assertEquals(1, model.getTotalRowCount());

            List<Cell[]> viewport = model.getViewport(10);
            assertEquals("worker-1", viewport.get(0)[0].display());
        }

        @Test
        void filter_caseInsensitive() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.setFilter("MAIN");
            assertEquals(1, model.getTotalRowCount());
        }

        @Test
        void filter_matchesAnyColumn() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.setFilter("BLOCKED");
            assertEquals(1, model.getTotalRowCount());

            List<Cell[]> viewport = model.getViewport(10);
            assertEquals("http-nio", viewport.get(0)[0].display());
        }

        @Test
        void filter_resetsScrollOffset() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.scroll(3);
            assertEquals(3, model.getScrollOffset());

            model.setFilter("work");
            assertEquals(0, model.getScrollOffset());
        }

        @Test
        void filter_emptyString_showsAllRows() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.setFilter("worker");
            assertEquals(1, model.getTotalRowCount());

            model.setFilter("");
            assertEquals(5, model.getTotalRowCount());
        }

        @Test
        void filter_noMatch_zeroRows() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.setFilter("nonexistent");
            assertEquals(0, model.getTotalRowCount());
        }

        @Test
        void filter_combinedWithSort() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.toggleSort(1); // CPU TIME descending
            model.setFilter("RUNNABLE"); // matches main and worker-1

            List<Cell[]> viewport = model.getViewport(10);
            assertEquals(2, viewport.size());
            // Should be sorted: main (5.2s) before worker-1 (3.1s)
            assertEquals("main", viewport.get(0)[0].display());
            assertEquals("worker-1", viewport.get(1)[0].display());
        }
    }

    @Nested
    class ScrollingTests {

        @Test
        void scroll_clampedAtZero() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.scroll(-10);
            assertEquals(0, model.getScrollOffset());
        }

        @Test
        void scroll_clampedAtMax() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.scroll(100);
            assertEquals(4, model.getScrollOffset()); // max = rows.size() - 1
        }

        @Test
        void scrollToTop_resetsOffset() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.scroll(3);
            model.scrollToTop();
            assertEquals(0, model.getScrollOffset());
        }

        @Test
        void scrollToBottom_goesToLastRow() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.scrollToBottom();
            assertEquals(4, model.getScrollOffset());
        }

        @Test
        void horizontalScroll_clampedAtZero() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.scrollHorizontal(-100);
            assertEquals(0, model.getHorizontalOffset());
        }

        @Test
        void horizontalScroll_movesRight() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.scrollHorizontal(8);
            assertEquals(8, model.getHorizontalOffset());
        }

        @Test
        void viewport_respectsScrollOffset() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.scroll(2);

            List<Cell[]> viewport = model.getViewport(2);
            // Should show rows starting from offset 2
            assertEquals(2, viewport.size());
            assertEquals("gc-thread", viewport.get(0)[0].display());
            assertEquals("http-nio", viewport.get(1)[0].display());
        }
    }

    @Nested
    class LiveViewStateTests {

        @Test
        void fromStructured_tableOutput_createsModel() {
            AnalyzerOutput.TableOutput tableOutput = new AnalyzerOutput.TableOutput(List.of(), sampleTable());
            LiveViewState state = LiveViewState.fromStructured(tableOutput);

            assertNotNull(state.model());
            assertEquals(5, state.model().getTotalRowCount());
            assertEquals(0, state.activeTab());
        }

        @Test
        void fromStructured_textOutput_createsTextModeModel() {
            AnalyzerOutput.TextOutput textOutput = new AnalyzerOutput.TextOutput("Line 1\nLine 2\nLine 3");
            LiveViewState state = LiveViewState.fromStructured(textOutput);

            assertNotNull(state.model());
            assertTrue(state.model().isTextMode());
            assertEquals(3, state.model().getTotalRowCount());
        }

        @Test
        void fromStructured_compositeOutput_hasTabsAndModel() {
            LiveViewState state = LiveViewState.fromStructured(sampleComposite());

            assertEquals(List.of("All", "threads", "locks"), state.tabNames());
            assertNotNull(state.model());
            assertEquals(0, state.activeTab());
        }

        @Test
        void withNextTab_navigatesForward() {
            LiveViewState state = LiveViewState.fromStructured(sampleComposite());
            state = state.withNextTab(); // tab 1: threads
            assertEquals(1, state.activeTab());

            state = state.withNextTab(); // tab 2: locks
            assertEquals(2, state.activeTab());
        }

        @Test
        void withNextTab_wrapsAround() {
            LiveViewState state = LiveViewState.fromStructured(sampleComposite());
            state = state.withNextTab(); // 1
            state = state.withNextTab(); // 2
            state = state.withNextTab(); // 0 (wrap)
            assertEquals(0, state.activeTab());
        }

        @Test
        void withPreviousTab_wrapsAround() {
            LiveViewState state = LiveViewState.fromStructured(sampleComposite());
            state = state.withPreviousTab(); // from 0 → wraps to 2
            assertEquals(2, state.activeTab());
        }

        @Test
        void displayOutput_allTab_returnsFullOutput() {
            LiveViewState state = LiveViewState.fromStructured(sampleComposite());
            String display = state.displayOutput();
            // "All" tab shows the full rendered output
            assertTrue(display.contains("THREAD"));
            assertTrue(display.contains("LOCK"));
        }

        @Test
        void displayOutput_specificTab_showsSectionOnly() {
            LiveViewState state = LiveViewState.fromStructured(sampleComposite());
            state = state.withNextTab().withNextTab(); // "locks" tab
            String display = state.displayOutput();
            assertTrue(display.contains("LOCK"));
            assertFalse(display.contains("CPU TIME"));
        }

        @Test
        void refreshFromResult_preservesSortAndFilter() {
            LiveViewState state = LiveViewState.fromStructured(sampleComposite());
            state = state.withNextTab(); // "threads" tab
            state.model().toggleSort(1); // sort by CPU TIME
            state.model().setFilter("main");

            // Simulate new data arriving
            AnalyzerResult newResult = AnalyzerResult.ok(sampleComposite());
            LiveViewState refreshed = state.refreshFromResult(newResult);

            assertEquals(1, refreshed.activeTab());
            assertNotNull(refreshed.model());
            assertEquals(1, refreshed.model().getSortColumn());
            assertEquals("main", refreshed.model().getFilterText());
        }

        @Test
        void refreshFromResult_preservesScrollOffsets() {
            LiveViewState state = LiveViewState.fromStructured(sampleComposite());
            state = state.withNextTab();
            state.model().scroll(2);
            state.model().scrollHorizontal(5);

            AnalyzerResult newResult = AnalyzerResult.ok(sampleComposite());
            LiveViewState refreshed = state.refreshFromResult(newResult);

            assertEquals(2, refreshed.model().getScrollOffset());
            assertEquals(5, refreshed.model().getHorizontalOffset());
        }

        @Test
        void refreshFromResult_preservesSecondarySorts() {
            LiveViewState state = LiveViewState.fromStructured(sampleComposite());
            state = state.withNextTab();
            state.model().toggleSort(3); // primary: STATE
            state.model().addSecondarySort(1); // secondary: CPU TIME

            AnalyzerResult newResult = AnalyzerResult.ok(sampleComposite());
            LiveViewState refreshed = state.refreshFromResult(newResult);

            assertEquals(3, refreshed.model().getSortColumn());
            assertEquals(1, refreshed.model().getSecondarySorts().size());
            assertEquals(1, refreshed.model().getSecondarySorts().get(0).column());
        }

        @Test
        void tabSwitch_createsNewModelForDifferentSection() {
            LiveViewState state = LiveViewState.fromStructured(sampleComposite());
            state = state.withNextTab(); // threads tab
            assertEquals(5, state.model().getRawRowCount());

            state = state.withNextTab(); // locks tab
            assertEquals(2, state.model().getRawRowCount());
        }

        @Test
        void empty_hasNoModel() {
            LiveViewState state = LiveViewState.empty();
            assertNull(state.model());
            assertNull(state.structured());
            assertEquals("", state.fullOutput());
        }
    }

    @Nested
    class InteractiveRendererTests {

        @Test
        void filterMode_entryAndExit() {
            InteractiveRenderer renderer = new InteractiveRenderer(null);
            assertFalse(renderer.isFilterMode());

            renderer.enterFilterMode();
            assertTrue(renderer.isFilterMode());

            renderer.exitFilterMode();
            assertFalse(renderer.isFilterMode());
        }

        @Test
        void filterInput_appendAndBackspace() {
            InteractiveRenderer renderer = new InteractiveRenderer(null);
            renderer.enterFilterMode();

            renderer.appendFilterChar('h');
            renderer.appendFilterChar('e');
            renderer.appendFilterChar('l');
            assertEquals("hel", renderer.getFilterInput());

            renderer.backspaceFilter();
            assertEquals("he", renderer.getFilterInput());
        }

        @Test
        void backspace_onEmptyFilter_noError() {
            InteractiveRenderer renderer = new InteractiveRenderer(null);
            renderer.backspaceFilter(); // should not throw
            assertEquals("", renderer.getFilterInput());
        }

        @Test
        void setFilterInput_replacesExisting() {
            InteractiveRenderer renderer = new InteractiveRenderer(null);
            renderer.appendFilterChar('x');
            renderer.setFilterInput("new-value");
            assertEquals("new-value", renderer.getFilterInput());
        }

        @Test
        void statusInfo_setAndGet() {
            InteractiveRenderer renderer = new InteractiveRenderer(null);
            renderer.setStatusInfo("PID 12345 (MyApp)");
            // No crash; API works (no getter exposed, but no exception)
        }
    }

    @Nested
    class KeyEventTests {

        @Test
        void charEvent_holdCharValue() {
            KeyEvent event = KeyEvent.ofChar('q');
            assertInstanceOf(KeyEvent.Char.class, event);
            assertEquals('q', ((KeyEvent.Char) event).ch());
        }

        @Test
        void specialEvents_haveCorrectTypes() {
            assertEquals(KeyEvent.Type.TAB, ((KeyEvent.Special) KeyEvent.TAB).type());
            assertEquals(KeyEvent.Type.SHIFT_TAB, ((KeyEvent.Special) KeyEvent.SHIFT_TAB).type());
            assertEquals(KeyEvent.Type.UP, ((KeyEvent.Special) KeyEvent.UP).type());
            assertEquals(KeyEvent.Type.DOWN, ((KeyEvent.Special) KeyEvent.DOWN).type());
            assertEquals(KeyEvent.Type.PAGE_UP, ((KeyEvent.Special) KeyEvent.PAGE_UP).type());
            assertEquals(KeyEvent.Type.PAGE_DOWN, ((KeyEvent.Special) KeyEvent.PAGE_DOWN).type());
            assertEquals(KeyEvent.Type.HOME, ((KeyEvent.Special) KeyEvent.HOME).type());
            assertEquals(KeyEvent.Type.END, ((KeyEvent.Special) KeyEvent.END).type());
        }
    }

    @Nested
    class RenderingTests {

        @Test
        void renderViewport_includesHeaderAndSeparator() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            List<String> lines = model.renderViewport(10, 200);

            // header + separator + 5 data rows = 7 lines
            assertEquals(7, lines.size());
            assertTrue(lines.get(0).contains("THREAD"));
            assertTrue(lines.get(0).contains("CPU TIME"));
            assertTrue(lines.get(1).contains("---"));
        }

        @Test
        void renderViewport_respectsMaxRows() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            List<String> lines = model.renderViewport(2, 200);

            // header + separator + 2 data rows = 4 lines
            assertEquals(4, lines.size());
        }

        @Test
        void renderViewport_withSort_reordersRows() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.toggleSort(2); // Sort by CPU% descending

            List<String> lines = model.renderViewport(10, 200);
            // First data row (index 2) should contain "main" with 45%
            assertTrue(lines.get(2).contains("main"));
            assertTrue(lines.get(2).contains("45%"));
        }

        @Test
        void renderViewport_withColor_hasAnsiCodes() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.setColorEnabled(true);

            List<String> lines = model.renderViewport(10, 200);
            // Data row for "main" should contain green ANSI code (RUNNABLE)
            String mainRow = lines.get(2);
            assertTrue(mainRow.contains("\033[32m"), "RUNNABLE should be green");
        }

        @Test
        void renderViewport_withHorizontalScroll() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.scrollHorizontal(5);

            List<String> lines = model.renderViewport(10, 200);
            // Header should be shifted — "THREAD" starts at col 0, so after 5 chars it should be trimmed
            assertFalse(lines.get(0).startsWith("THREAD"));
        }

        @Test
        void renderViewport_textMode_noHeaderOrSeparator() {
            TableViewModel model = TableViewModel.forLines(List.of("alpha", "beta", "gamma"));
            List<String> lines = model.renderViewport(10, 200);

            assertEquals(3, lines.size());
            assertTrue(lines.get(0).contains("alpha"));
        }

        @Test
        void renderViewport_withFilter_showsOnlyMatchingRows() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of());
            model.setFilter("worker");

            List<String> lines = model.renderViewport(10, 200);
            // header + separator + 1 data row = 3 lines
            assertEquals(3, lines.size());
            assertTrue(lines.get(2).contains("worker-1"));
        }

        @Test
        void renderViewport_withPreamble() {
            TableViewModel model = TableViewModel.fromModel(sampleTable(), List.of("Top 5 threads by CPU:"));
            List<String> preamble = model.getPreambleLines();
            assertEquals(1, preamble.size());
            assertEquals("Top 5 threads by CPU:", preamble.get(0));
        }
    }

    @Nested
    class IntervalAdjustmentTests {

        @Test
        void intervalIncrease_simulatedViaKeyChar() {
            // This tests the interval logic conceptually:
            // '+' increases interval, '-' decreases it (handled in LiveModeRunner)
            // We verify that the Duration concept works with boundaries
            java.time.Duration interval = java.time.Duration.ofSeconds(5);

            // Simulate '+' key
            interval = java.time.Duration.ofSeconds(Math.min(interval.getSeconds() + 1, 300));
            assertEquals(6, interval.getSeconds());

            // Simulate '-' key
            interval = java.time.Duration.ofSeconds(Math.max(interval.getSeconds() - 1, 1));
            assertEquals(5, interval.getSeconds());

            // Test minimum bound
            interval = java.time.Duration.ofSeconds(1);
            interval = java.time.Duration.ofSeconds(Math.max(interval.getSeconds() - 1, 1));
            assertEquals(1, interval.getSeconds()); // clamped to 1

            // Test maximum bound
            interval = java.time.Duration.ofSeconds(300);
            interval = java.time.Duration.ofSeconds(Math.min(interval.getSeconds() + 1, 300));
            assertEquals(300, interval.getSeconds()); // clamped to 300
        }
    }
}
