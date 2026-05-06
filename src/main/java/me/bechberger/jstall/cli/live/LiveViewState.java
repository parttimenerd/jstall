package me.bechberger.jstall.cli.live;

import me.bechberger.jstall.analyzer.AnalyzerOutput;
import me.bechberger.jstall.analyzer.AnalyzerResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable container for interactive live-mode view state.
 * Encapsulates tab parsing/selection and model state transfer between samples.
 */
public record LiveViewState(String fullOutput,
                            AnalyzerOutput structured,
                            int activeTab,
                            TableViewModel model) {

    public static LiveViewState empty() {
        return new LiveViewState("", null, 0, null);
    }

    public static LiveViewState fromResult(AnalyzerResult result) {
        return fromStructured(result.structured());
    }

    public static LiveViewState fromStructured(AnalyzerOutput output) {
        String rendered = output.render();
        if (output instanceof AnalyzerOutput.TableOutput tableOutput) {
            TableViewModel model = TableViewModel.fromModel(tableOutput.table(), tableOutput.preambleLines());
            return new LiveViewState(rendered, output, 0, model);
        } else if (output instanceof AnalyzerOutput.CompositeOutput composite) {
            TableViewModel model = modelForCompositeTab(composite, 0);
            return new LiveViewState(rendered, output, 0, model);
        } else if (output instanceof AnalyzerOutput.TextOutput textOutput) {
            TableViewModel model = TableViewModel.forLines(textOutput.text().lines().toList());
            return new LiveViewState(rendered, output, 0, model);
        }
        throw new IllegalArgumentException("Unknown AnalyzerOutput type: " + output.getClass());
    }

    public LiveViewState refreshFromResult(AnalyzerResult result) {
        LiveViewState next = fromStructured(result.structured()).withActiveTab(activeTab);
        if (next.model != null && model != null) {
            transferModelState(model, next.model);
        }
        return next;
    }

    public LiveViewState withNextTab() {
        int tabCount = tabCount();
        if (tabCount <= 1) {
            return this;
        }
        return withActiveTab((activeTab + 1) % tabCount);
    }

    public LiveViewState withPreviousTab() {
        int tabCount = tabCount();
        if (tabCount <= 1) {
            return this;
        }
        return withActiveTab((activeTab - 1 + tabCount) % tabCount);
    }

    public List<String> tabNames() {
        if (structured instanceof AnalyzerOutput.CompositeOutput composite) {
            List<String> names = new ArrayList<>();
            names.add("All");
            for (var section : composite.sections()) {
                names.add(section.name());
            }
            return names;
        }
        return null;
    }

    public String displayOutput() {
        if (structured instanceof AnalyzerOutput.CompositeOutput composite) {
            if (activeTab == 0) return fullOutput;
            int idx = activeTab - 1;
            if (idx < composite.sections().size()) {
                return composite.sections().get(idx).content().render();
            }
            return fullOutput;
        }
        return fullOutput;
    }

    private int tabCount() {
        if (structured instanceof AnalyzerOutput.CompositeOutput composite) {
            return composite.sections().size() + 1; // +1 for "All"
        }
        return 0;
    }

    /**
     * Returns a new LiveViewState with the given active tab.
     */
    public LiveViewState withActiveTab(int candidateTab) {
        int maxTab = Math.max(0, tabCount() - 1);
        int clampedTab = Math.max(0, Math.min(candidateTab, maxTab));

        TableViewModel nextModel;
        if (structured instanceof AnalyzerOutput.CompositeOutput composite) {
            nextModel = modelForCompositeTab(composite, clampedTab);
        } else if (structured instanceof AnalyzerOutput.TableOutput tableOutput) {
            nextModel = TableViewModel.fromModel(tableOutput.table(), tableOutput.preambleLines());
        } else {
            nextModel = TableViewModel.forLines(fullOutput.lines().toList());
        }

        if (nextModel != null && model != null && clampedTab == activeTab) {
            transferModelState(model, nextModel);
        }
        return new LiveViewState(fullOutput, structured, clampedTab, nextModel);
    }

    private static TableViewModel modelForCompositeTab(AnalyzerOutput.CompositeOutput composite, int tab) {
        if (tab == 0) {
            // "All" tab: try the first table section
            for (var section : composite.sections()) {
                if (section.content() instanceof AnalyzerOutput.TableOutput tableOutput) {
                    return TableViewModel.fromModel(tableOutput.table(), tableOutput.preambleLines());
                }
            }
            // Try parsing the rendered text as a table
            TableViewModel parsed = TableViewModel.parse(composite.render());
            if (parsed != null) return parsed;
            return TableViewModel.forLines(composite.render().lines().toList());
        }
        int idx = tab - 1;
        if (idx >= composite.sections().size()) return null;
        var content = composite.sections().get(idx).content();
        if (content instanceof AnalyzerOutput.TableOutput tableOutput) {
            return TableViewModel.fromModel(tableOutput.table(), tableOutput.preambleLines());
        }
        // Try parsing rendered text as a table before falling back to plain lines
        TableViewModel parsed = TableViewModel.parse(content.render());
        if (parsed != null) return parsed;
        return TableViewModel.forLines(content.render().lines().toList());
    }

    private static void transferModelState(TableViewModel oldModel, TableViewModel newModel) {
        if (oldModel.getSortColumn() >= 0) {
            newModel.toggleSort(oldModel.getSortColumn());
            if (oldModel.isSortAscending() != newModel.isSortAscending()) {
                newModel.toggleSort(oldModel.getSortColumn());
            }
            // Transfer secondary sorts
            for (TableViewModel.SortKey sk : oldModel.getSecondarySorts()) {
                newModel.addSecondarySort(sk.column());
                // addSecondarySort defaults to descending; toggle if ascending needed
                List<TableViewModel.SortKey> current = newModel.getSecondarySorts();
                if (!current.isEmpty()) {
                    TableViewModel.SortKey last = current.get(current.size() - 1);
                    if (last.column() == sk.column() && last.ascending() != sk.ascending()) {
                        newModel.addSecondarySort(sk.column()); // toggle direction
                    }
                }
            }
        }
        newModel.setFilter(oldModel.getFilterText());
        newModel.setScrollOffset(oldModel.getScrollOffset());
        newModel.setHorizontalOffset(oldModel.getHorizontalOffset());
    }
}