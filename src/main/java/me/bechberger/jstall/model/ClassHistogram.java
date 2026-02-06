package me.bechberger.jstall.model;

import java.util.Comparator;
import java.util.List;

/**
 * Parsed class histogram.
 */
public record ClassHistogram(List<ClassHistogramEntry> entries) {

    public long totalBytes() {
        return entries.stream().mapToLong(ClassHistogramEntry::bytes).sum();
    }

    public long totalInstances() {
        return entries.stream().mapToLong(ClassHistogramEntry::instances).sum();
    }

    public List<ClassHistogramEntry> topByBytes(int n) {
        return entries.stream()
            .sorted(Comparator.comparingLong(ClassHistogramEntry::bytes).reversed())
            .limit(n)
            .toList();
    }

    public List<ClassHistogramEntry> topByInstances(int n) {
        return entries.stream()
            .sorted(Comparator.comparingLong(ClassHistogramEntry::instances).reversed())
            .limit(n)
            .toList();
    }
}