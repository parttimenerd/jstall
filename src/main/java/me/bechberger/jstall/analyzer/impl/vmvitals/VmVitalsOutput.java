package me.bechberger.jstall.analyzer.impl.vmvitals;

import java.util.List;
import java.util.Set;

/**
 * Top-level VM vitals output model: PID, version, legend, sections, and pre-filtered legend.
 */
public record VmVitalsOutput(
    long pid,
    String version,
    VmVitalsLegend legend,
    List<VmVitalsSection> sections,
    VmVitalsLegend filteredLegend
) {
    public VmVitalsOutput {
        sections = List.copyOf(sections);
    }

    public static VmVitalsOutput of(long pid, String version, VmVitalsLegend legend,
                                    List<VmVitalsSection> sections) {
        List<String> orderedColumns = sections.stream()
            .flatMap(s -> s.columnNames().stream())
            .toList();
        VmVitalsLegend filtered = orderedColumns.isEmpty()
            ? new VmVitalsLegend(List.of(), List.of())
            : legend.filterByActiveColumns(orderedColumns);
        return new VmVitalsOutput(pid, version, legend, sections, filtered);
    }

    public Set<String> activeConditions() {
        return filteredLegend.activeConditionTags();
    }
}
