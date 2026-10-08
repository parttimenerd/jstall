package me.bechberger.jstall.analyzer.impl.vmvitals;

import java.util.Set;

/**
 * Legend entry: key, description, and condition tags applicable to this entry.
 * Example: key="heap-comm", description="Java Heap Size, committed", conditionTags={}
 * or key="meta-csc", description="Class Space Size, committed [cs]", conditionTags={"cs"}
 */
public record LegendEntry(
    String key,
    String description,
    Set<String> conditionTags
) {
    public LegendEntry {
        conditionTags = Set.copyOf(conditionTags);
    }

    /**
     * Extracts the short name from the key (the part after the last '-').
     * E.g., "heap-comm" → "comm", "meta-csc" → "csc"
     */
    public String shortName() {
        int lastDash = key.lastIndexOf('-');
        return lastDash >= 0 ? key.substring(lastDash + 1) : key;
    }

    /**
     * Extracts the prefix (the part before the last '-').
     * E.g., "heap-comm" → "heap", "meta-csc" → "meta"
     */
    public String prefix() {
        int lastDash = key.lastIndexOf('-');
        return lastDash >= 0 ? key.substring(0, lastDash) : "";
    }
}

