package me.bechberger.jstall.analyzer.impl.vmvitals;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The legend section of VM vitals: ordered entries and condition notes.
 */
public record VmVitalsLegend(
    List<LegendEntry> entries,
    List<LegendCondition> conditions
) {
    public VmVitalsLegend {
        entries = List.copyOf(entries);
        conditions = List.copyOf(conditions);
    }

    /**
     * Filters the legend against the ordered short-name sequence printed in the table.
     * This is more precise than set-based filtering because duplicate short names such as
     * "comm" or "used" are matched in the same order they appear in the legend/table.
     */
    public VmVitalsLegend filterByActiveColumns(List<String> orderedActiveShortNames) {
        if (orderedActiveShortNames == null || orderedActiveShortNames.isEmpty()) {
            return new VmVitalsLegend(List.of(), List.of());
        }

        List<LegendEntry> matchedEntries = new ArrayList<>();
        int activeIndex = 0;
        for (LegendEntry entry : entries) {
            if (activeIndex >= orderedActiveShortNames.size()) {
                break;
            }
            if (entry.shortName().equals(orderedActiveShortNames.get(activeIndex))) {
                matchedEntries.add(entry);
                activeIndex++;
            }
        }

        Set<String> usedTags = matchedEntries.stream()
            .flatMap(e -> e.conditionTags().stream())
            .collect(Collectors.toSet());

        List<LegendCondition> filteredConditions = conditions.stream()
            .filter(c -> usedTags.contains(c.tag()))
            .toList();

        return new VmVitalsLegend(matchedEntries, filteredConditions);
    }

    public Set<String> activeConditionTags() {
        return entries.stream()
            .flatMap(e -> e.conditionTags().stream())
            .collect(Collectors.toSet());
    }
}

