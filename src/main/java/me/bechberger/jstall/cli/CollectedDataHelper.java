package me.bechberger.jstall.cli;

import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jstall.provider.requirement.DataRequirement;
import me.bechberger.jstall.util.JcmdOutputParsers;

import java.util.*;

/**
 * Shared utilities for processing collected JVM data.
 */
public class CollectedDataHelper {

    private CollectedDataHelper() {}

    /**
     * Converts raw DataRequirement-keyed collection results into a type-keyed map,
     * sorted by timestamp within each type.
     */
    public static Map<String, List<CollectedData>> toByTypeMap(Map<DataRequirement, List<CollectedData>> raw) {
        Map<String, List<CollectedData>> byType = new HashMap<>();
        for (Map.Entry<DataRequirement, List<CollectedData>> entry : raw.entrySet()) {
            String type = entry.getKey().getType();
            if (type != null && !type.isBlank()) {
                byType.computeIfAbsent(type, __ -> new ArrayList<>()).addAll(entry.getValue());
            }
        }
        sortByTimestamp(byType);
        return byType;
    }

    /**
     * Extracts system properties from the collected data map.
     */
    public static Map<String, String> extractSystemProps(Map<String, List<CollectedData>> byType) {
        List<CollectedData> props = byType.getOrDefault("system-properties", List.of());
        if (props.isEmpty()) return null;
        return JcmdOutputParsers.parseVmSystemProperties(props.get(0).rawData());
    }

    /**
     * Merges two maps of collected data, combining lists and sorting by timestamp.
     */
    public static <K> Map<K, List<CollectedData>> merge(Map<K, List<CollectedData>> first,
                                                         Map<K, List<CollectedData>> second) {
        Map<K, List<CollectedData>> merged = new LinkedHashMap<>();
        for (Map.Entry<K, List<CollectedData>> entry : first.entrySet()) {
            merged.computeIfAbsent(entry.getKey(), __ -> new ArrayList<>()).addAll(entry.getValue());
        }
        for (Map.Entry<K, List<CollectedData>> entry : second.entrySet()) {
            merged.computeIfAbsent(entry.getKey(), __ -> new ArrayList<>()).addAll(entry.getValue());
        }
        sortByTimestamp(merged);
        return merged;
    }

    private static <K> void sortByTimestamp(Map<K, List<CollectedData>> map) {
        map.replaceAll((__, samples) -> samples.stream()
                .sorted(Comparator.comparingLong(CollectedData::timestamp))
                .toList());
    }
}
