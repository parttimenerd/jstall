package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jstall.provider.requirement.DataRequirements;
import me.bechberger.jstall.util.TablePrinter;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyzes GC.heap_info samples.
 * <p>
 * Shows absolute values from the last dump plus the change compared to the previous dump.
 */
public class GcHeapInfoAnalyzer implements Analyzer {

    @Override
    public String name() {
        return "gc-heap-info";
    }

    @Override
    public Set<String> supportedOptions() {
        return Set.of();
    }

    @Override
    public DumpRequirement dumpRequirement() {
        return DumpRequirement.ANY;
    }

    @Override
    public DataRequirements getDataRequirements(Map<String, Object> options) {
        int count = Math.max(2, getIntOption(options, "dumps", 2));
        long intervalMs = getLongOption(options, "interval", 5000L);
        return DataRequirements.builder()
            .addThreadDump()
            .addJcmd("GC.heap_info", count, intervalMs)
            .build();
    }

    @Override
    public AnalyzerResult analyze(ResolvedData data, Map<String, Object> options) {
        String output = formatGcHeapInfoAnalysis(data.collectedData("gc-heap-info"));
        if (output.isEmpty()) {
            return AnalyzerResult.nothing();
        }
        return AnalyzerResult.ok(output);
    }

    private int getIntOption(Map<String, Object> options, String key, int defaultValue) {
        Object value = options.get(key);
        if (value instanceof Integer i) {
            return i;
        } else if (value instanceof Number n) {
            return n.intValue();
        }
        return defaultValue;
    }

    private long getLongOption(Map<String, Object> options, String key, long defaultValue) {
        Object value = options.get(key);
        if (value instanceof Long l) {
            return l;
        } else if (value instanceof Number n) {
            return n.longValue();
        }
        return defaultValue;
    }

    private String formatGcHeapInfoAnalysis(List<CollectedData> samples) {
        if (samples == null || samples.isEmpty()) {
            return "";
        }

        HeapInfo latest = parseGcHeapInfo(samples.get(samples.size() - 1).rawData());
        if (latest == null) {
            return "";
        }

        HeapInfo previous = null;
        if (samples.size() > 1) {
            previous = parseGcHeapInfo(samples.get(samples.size() - 2).rawData());
        }

        List<Row> rows = List.of(
            new Row("Heap total", formatKib(latest.heapTotalK()), formatHumanKib(latest.heapTotalK()), formatDelta(latest.heapTotalK(), previous == null ? null : previous.heapTotalK())),
            new Row("Heap used", formatKib(latest.heapUsedK()), formatHumanKib(latest.heapUsedK()) + " | " + String.format(Locale.ROOT, "%.1f%%", latest.heapUsagePercent()), formatDelta(latest.heapUsedK(), previous == null ? null : previous.heapUsedK())),
            new Row("Young regions", latest.youngRegionCount() + " regions, " + formatKib(latest.youngRegionTotalK()), formatHumanKib(latest.youngRegionTotalK()), formatDelta(latest.youngRegionTotalK(), previous == null ? null : previous.youngRegionTotalK())),
            new Row("Survivor regions", latest.survivorRegionCount() + " regions, " + formatKib(latest.survivorRegionTotalK()), formatHumanKib(latest.survivorRegionTotalK()), formatDelta(latest.survivorRegionTotalK(), previous == null ? null : previous.survivorRegionTotalK())),
            new Row("Metaspace used", formatKib(latest.metaspaceUsedK()), formatHumanKib(latest.metaspaceUsedK()), formatDelta(latest.metaspaceUsedK(), previous == null ? null : previous.metaspaceUsedK())),
            new Row("Metaspace committed", formatKib(latest.metaspaceCommittedK()), formatHumanKib(latest.metaspaceCommittedK()), formatDelta(latest.metaspaceCommittedK(), previous == null ? null : previous.metaspaceCommittedK())),
            new Row("Metaspace reserved", formatKib(latest.metaspaceReservedK()), formatHumanKib(latest.metaspaceReservedK()), formatDelta(latest.metaspaceReservedK(), previous == null ? null : previous.metaspaceReservedK())),
            new Row("Class space used", formatKib(latest.classSpaceUsedK()), formatHumanKib(latest.classSpaceUsedK()), formatDelta(latest.classSpaceUsedK(), previous == null ? null : previous.classSpaceUsedK())),
            new Row("Class space committed", formatKib(latest.classSpaceCommittedK()), formatHumanKib(latest.classSpaceCommittedK()), formatDelta(latest.classSpaceCommittedK(), previous == null ? null : previous.classSpaceCommittedK())),
            new Row("Class space reserved", formatKib(latest.classSpaceReservedK()), formatHumanKib(latest.classSpaceReservedK()), formatDelta(latest.classSpaceReservedK(), previous == null ? null : previous.classSpaceReservedK()))
        );

        TablePrinter table = new TablePrinter()
            .addColumn("Metric", TablePrinter.Alignment.LEFT)
            .addColumn("Value", TablePrinter.Alignment.RIGHT)
            .addColumn("Details", TablePrinter.Alignment.LEFT)
            .addColumn("Δ", TablePrinter.Alignment.RIGHT);

        for (Row row : rows) {
            table.addRow(row.metric(), row.value(), row.details(), row.delta());
        }

        return "GC.heap_info (last dump absolute + change):\n" + table.render();
    }

    private String formatKib(long kib) {
        return String.format(Locale.ROOT, "%,dK", kib);
    }

    private String formatHumanKib(long kib) {
        double value = kib;
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        int unitIndex = 0;
        while (value >= 1024.0 && unitIndex < units.length - 1) {
            value /= 1024.0;
            unitIndex++;
        }
        if (unitIndex == 0) {
            return String.format(Locale.ROOT, "%,d %s", (long) value, units[unitIndex]);
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unitIndex]);
    }

    private String formatDelta(long latest, Long previous) {
        if (previous == null) {
            return "n/a";
        }
        long delta = latest - previous;
        String sign = delta >= 0 ? "+" : "";
        long absDelta = Math.abs(delta);
        return "Δ " + sign + String.format(Locale.ROOT, "%,d", delta) + "K / " + sign + formatHumanKib(absDelta);
    }

    private HeapInfo parseGcHeapInfo(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        Pattern heapPattern = Pattern.compile(".*heap\\s+total\\s+(\\d+)K,\\s+used\\s+(\\d+)K.*");
        Pattern regionPattern = Pattern.compile("region size\\s+\\d+K,\\s+(\\d+) young \\((\\d+)K\\),\\s+(\\d+) survivors \\((\\d+)K\\).*");
        Pattern metaspacePattern = Pattern.compile("Metaspace\\s+used\\s+(\\d+)K,\\s+committed\\s+(\\d+)K,\\s+reserved\\s+(\\d+)K.*");
        Pattern classSpacePattern = Pattern.compile("class space\\s+used\\s+(\\d+)K,\\s+committed\\s+(\\d+)K,\\s+reserved\\s+(\\d+)K.*");

        Long heapTotal = null;
        Long heapUsed = null;
        Integer youngRegionCount = null;
        Long youngRegionTotal = null;
        Integer survivorRegionCount = null;
        Long survivorRegionTotal = null;
        Long metaspaceUsed = null;
        Long metaspaceCommitted = null;
        Long metaspaceReserved = null;
        Long classSpaceUsed = null;
        Long classSpaceCommitted = null;
        Long classSpaceReserved = null;

        for (String line : raw.lines().toList()) {
            String trimmed = line.trim();
            Matcher heapMatcher = heapPattern.matcher(trimmed);
            if (heapMatcher.matches()) {
                heapTotal = Long.parseLong(heapMatcher.group(1));
                heapUsed = Long.parseLong(heapMatcher.group(2));
                continue;
            }

            Matcher regionMatcher = regionPattern.matcher(trimmed);
            if (regionMatcher.matches()) {
                youngRegionCount = Integer.parseInt(regionMatcher.group(1));
                youngRegionTotal = Long.parseLong(regionMatcher.group(2));
                survivorRegionCount = Integer.parseInt(regionMatcher.group(3));
                survivorRegionTotal = Long.parseLong(regionMatcher.group(4));
                continue;
            }

            Matcher metaspaceMatcher = metaspacePattern.matcher(trimmed);
            if (metaspaceMatcher.matches()) {
                metaspaceUsed = Long.parseLong(metaspaceMatcher.group(1));
                metaspaceCommitted = Long.parseLong(metaspaceMatcher.group(2));
                metaspaceReserved = Long.parseLong(metaspaceMatcher.group(3));
                continue;
            }

            Matcher classSpaceMatcher = classSpacePattern.matcher(trimmed);
            if (classSpaceMatcher.matches()) {
                classSpaceUsed = Long.parseLong(classSpaceMatcher.group(1));
                classSpaceCommitted = Long.parseLong(classSpaceMatcher.group(2));
                classSpaceReserved = Long.parseLong(classSpaceMatcher.group(3));
            }
        }

        if (heapTotal == null || youngRegionCount == null || youngRegionTotal == null || survivorRegionTotal == null || metaspaceCommitted == null || classSpaceCommitted == null) {
            return null;
        }

        return new HeapInfo(heapTotal,
            heapUsed,
            youngRegionCount,
            youngRegionTotal,
            survivorRegionCount,
            survivorRegionTotal,
            metaspaceUsed,
            metaspaceCommitted,
            metaspaceReserved,
            classSpaceUsed,
            classSpaceCommitted,
            classSpaceReserved);
    }

    private record HeapInfo(long heapTotalK,
                            long heapUsedK,
                            int youngRegionCount,
                            long youngRegionTotalK,
                            int survivorRegionCount,
                            long survivorRegionTotalK,
                            long metaspaceUsedK,
                            long metaspaceCommittedK,
                            long metaspaceReservedK,
                            long classSpaceUsedK,
                            long classSpaceCommittedK,
                            long classSpaceReservedK) {
        double heapUsagePercent() {
            if (heapTotalK == 0) {
                return 0;
            }
            return (heapUsedK * 100.0) / heapTotalK;
        }
    }

    private record Row(String metric, String value, String details, String delta) {
    }
}