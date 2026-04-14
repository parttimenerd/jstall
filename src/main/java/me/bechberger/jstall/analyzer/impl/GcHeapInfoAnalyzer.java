package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jstall.provider.requirement.DataRequirements;
import me.bechberger.jstall.util.TablePrinter;

import java.util.ArrayList;
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
        int count = Math.max(2, getIntOption(options, "dump-count", 2));
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

        List<Row> rows = new ArrayList<>();
        final HeapInfo prev = previous;
        rows.add(new Row("Heap total", formatKib(latest.heapTotalK()), formatHumanKib(latest.heapTotalK()),
            formatDelta(latest.heapTotalK(), prev == null ? null : prev.heapTotalK())));
        rows.add(new Row("Heap used",
            formatKib(latest.heapUsedK()) + " | " + String.format(Locale.ROOT, "%.1f%%", latest.heapUsagePercent()),
            formatHumanKib(latest.heapUsedK()),
            formatDelta(latest.heapUsedK(), prev == null ? null : prev.heapUsedK())));
        if (latest.youngRegionCount() != null && latest.youngRegionTotalK() != null) {
            rows.add(new Row("Young regions",
                latest.youngRegionCount() + " regions, " + formatKib(latest.youngRegionTotalK()),
                formatHumanKib(latest.youngRegionTotalK()),
                formatDelta(latest.youngRegionTotalK(), prev == null ? null : prev.youngRegionTotalK())));
            rows.add(new Row("Survivor regions",
                latest.survivorRegionCount() + " regions, " + formatKib(latest.survivorRegionTotalK()),
                formatHumanKib(latest.survivorRegionTotalK()),
                formatDelta(latest.survivorRegionTotalK(), prev == null ? null : prev.survivorRegionTotalK())));
        }
        if (latest.metaspaceCommittedK() != null) {
            rows.add(new Row("Metaspace used", formatKib(latest.metaspaceUsedK()), formatHumanKib(latest.metaspaceUsedK()),
                formatDelta(latest.metaspaceUsedK(), prev == null ? null : prev.metaspaceUsedK())));
            rows.add(new Row("Metaspace committed", formatKib(latest.metaspaceCommittedK()), formatHumanKib(latest.metaspaceCommittedK()),
                formatDelta(latest.metaspaceCommittedK(), prev == null ? null : prev.metaspaceCommittedK())));
            rows.add(new Row("Metaspace reserved", formatKib(latest.metaspaceReservedK()), formatHumanKib(latest.metaspaceReservedK()),
                formatDelta(latest.metaspaceReservedK(), prev == null ? null : prev.metaspaceReservedK())));
        }
        if (latest.classSpaceCommittedK() != null) {
            rows.add(new Row("Class space used", formatKib(latest.classSpaceUsedK()), formatHumanKib(latest.classSpaceUsedK()),
                formatDelta(latest.classSpaceUsedK(), prev == null ? null : prev.classSpaceUsedK())));
            rows.add(new Row("Class space committed", formatKib(latest.classSpaceCommittedK()), formatHumanKib(latest.classSpaceCommittedK()),
                formatDelta(latest.classSpaceCommittedK(), prev == null ? null : prev.classSpaceCommittedK())));
            rows.add(new Row("Class space reserved", formatKib(latest.classSpaceReservedK()), formatHumanKib(latest.classSpaceReservedK()),
                formatDelta(latest.classSpaceReservedK(), prev == null ? null : prev.classSpaceReservedK())));
        }

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

        private String formatKib(Long kib) {
            return kib == null ? "" : formatKib((long) kib);
        }

    private String formatKib(long kib) {
        return String.format(Locale.ROOT, "%,dK", kib);
    }

        private String formatHumanKib(Long kib) {
            return kib == null ? "" : formatHumanKib((long) kib);
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

    private String formatDelta(Long latest, Long previous) {
        if (previous == null || latest == null) {
            return "n/a";
        }
        long delta = latest - previous;
        long absDelta = Math.abs(delta);
        String signedK = (delta >= 0 ? "+" : "") + String.format(Locale.ROOT, "%,d", delta) + "K";
        String signedHuman = (delta >= 0 ? "+" : "-") + formatHumanKib(absDelta);
        return "Δ " + signedK + " / " + signedHuman;
    }

    private HeapInfo parseGcHeapInfo(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        // Old G1GC format: "heap total 12345K, used 123K"
        Pattern heapPatternOld = Pattern.compile(".*\\bheap\\s+total\\s+(\\d+)K,\\s+used\\s+(\\d+)K.*");
        // New G1GC format (JDK 21+): "garbage-first heap   total reserved NxK, committed NxK, used NxK"
        Pattern heapPatternNew = Pattern.compile(".*\\bheap\\s+total\\s+reserved\\s+\\d+K,\\s+committed\\s+(\\d+)K,\\s+used\\s+(\\d+)K.*");
        // Old region format: "region size 1024K, 5 young (5120K), 1 survivors (1024K)"
        Pattern regionPatternOld = Pattern.compile("region size\\s+\\d+K,\\s+(\\d+) young \\((\\d+)K\\),\\s+(\\d+) survivors \\((\\d+)K\\).*");
        // New region format (JDK 21+): "region size 8M, 1 eden (8M), 1 survivor (8M), ..."
        Pattern regionPatternNew = Pattern.compile("region size\\s+\\d+[KMG],\\s+(\\d+)\\s+eden\\s+\\((\\d+)([KMG])\\),\\s+(\\d+)\\s+survivor\\s+\\((\\d+)([KMG])\\).*");
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
            Matcher heapMatcher = heapPatternOld.matcher(trimmed);
            if (heapMatcher.matches()) {
                heapTotal = Long.parseLong(heapMatcher.group(1));
                heapUsed = Long.parseLong(heapMatcher.group(2));
                continue;
            }
            Matcher heapMatcherNew = heapPatternNew.matcher(trimmed);
            if (heapMatcherNew.matches()) {
                heapTotal = Long.parseLong(heapMatcherNew.group(1));
                heapUsed = Long.parseLong(heapMatcherNew.group(2));
                continue;
            }

            Matcher regionMatcher = regionPatternOld.matcher(trimmed);
            if (regionMatcher.matches()) {
                youngRegionCount = Integer.parseInt(regionMatcher.group(1));
                youngRegionTotal = Long.parseLong(regionMatcher.group(2));
                survivorRegionCount = Integer.parseInt(regionMatcher.group(3));
                survivorRegionTotal = Long.parseLong(regionMatcher.group(4));
                continue;
            }
            Matcher regionMatcherNew = regionPatternNew.matcher(trimmed);
            if (regionMatcherNew.matches()) {
                youngRegionCount = Integer.parseInt(regionMatcherNew.group(1));
                youngRegionTotal = toKilobytes(Long.parseLong(regionMatcherNew.group(2)), regionMatcherNew.group(3));
                survivorRegionCount = Integer.parseInt(regionMatcherNew.group(4));
                survivorRegionTotal = toKilobytes(Long.parseLong(regionMatcherNew.group(5)), regionMatcherNew.group(6));
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

        if (heapTotal == null || heapUsed == null) {
            return null;
        }

        return new HeapInfo(heapTotal, heapUsed,
            youngRegionCount, youngRegionTotal,
            survivorRegionCount, survivorRegionTotal,
            metaspaceUsed, metaspaceCommitted, metaspaceReserved,
            classSpaceUsed, classSpaceCommitted, classSpaceReserved);
    }

        private static long toKilobytes(long value, String unit) {
            return switch (unit.toUpperCase()) {
                case "G" -> value * 1024 * 1024;
                case "M" -> value * 1024;
                default  -> value; // K
            };
        }

    private record HeapInfo(long heapTotalK,
                            long heapUsedK,
                            Integer youngRegionCount,
                            Long youngRegionTotalK,
                            Integer survivorRegionCount,
                            Long survivorRegionTotalK,
                            Long metaspaceUsedK,
                            Long metaspaceCommittedK,
                            Long metaspaceReservedK,
                            Long classSpaceUsedK,
                            Long classSpaceCommittedK,
                            Long classSpaceReservedK) {
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