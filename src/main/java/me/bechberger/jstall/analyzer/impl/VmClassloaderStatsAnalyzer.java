package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jstall.provider.requirement.DataRequirements;
import me.bechberger.jstall.util.TablePrinter;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyze VM.classloader_stats output.
 * <p>
 * Aggregates rows by classloader type (ignoring pointer columns), sorts by class count,
 * and optionally shows growth/decline trend when multiple samples are available.
 */
public class VmClassloaderStatsAnalyzer implements Analyzer {

    private static final Pattern ROW_PATTERN = Pattern.compile(
        "^\\S+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(.+)$"
    );

    @Override
    public String name() {
        return "vm-classloader-stats";
    }

    @Override
    public Set<String> supportedOptions() {
        return Set.of("samples", "interval");
    }

    @Override
    public DumpRequirement dumpRequirement() {
        return DumpRequirement.ANY;
    }

    @Override
    public DataRequirements getDataRequirements(Map<String, Object> options) {
        int samples = getIntOption(options, "samples", 2);
        long intervalMs = getLongOption(options, "interval", 2000L);
        return DataRequirements.builder()
            .addThreadDump()
            .addJcmd("VM.classloader_stats", samples, intervalMs)
            .build();
    }

    @Override
    public AnalyzerResult analyze(ResolvedData data, Map<String, Object> options) {
        List<CollectedData> samples = data.collectedData("vm-classloader-stats");
        if (samples.isEmpty()) {
            return AnalyzerResult.nothing();
        }

        List<Map<String, AggregateRow>> parsedSamples = new ArrayList<>();
        for (CollectedData sample : samples) {
            Map<String, AggregateRow> parsed = parseByType(sample.rawData());
            if (!parsed.isEmpty()) {
                parsedSamples.add(parsed);
            }
        }

        if (parsedSamples.isEmpty()) {
            return AnalyzerResult.ok("VM.classloader_stats not available (or no parseable rows)");
        }

        String output = formatTable(parsedSamples);
        return output.isBlank() ? AnalyzerResult.nothing() : AnalyzerResult.ok(output);
    }

    private String formatTable(List<Map<String, AggregateRow>> parsedSamples) {
        Map<String, AggregateRow> first = parsedSamples.get(0);
        Map<String, AggregateRow> latest = parsedSamples.get(parsedSamples.size() - 1);
        boolean hasTrend = parsedSamples.size() > 1;

        Set<String> allTypes = new LinkedHashSet<>();
        allTypes.addAll(latest.keySet());
        allTypes.addAll(first.keySet());

        List<String> sortedTypes = new ArrayList<>(allTypes);
        sortedTypes.sort((a, b) -> Long.compare(
            latest.getOrDefault(b, AggregateRow.ZERO).classes,
            latest.getOrDefault(a, AggregateRow.ZERO).classes
        ));

        TablePrinter table = new TablePrinter()
            .addColumn("Type", TablePrinter.Alignment.LEFT)
            .addColumn("Classes", TablePrinter.Alignment.RIGHT)
            .addColumn("ChunkSz", TablePrinter.Alignment.RIGHT)
            .addColumn("BlockSz", TablePrinter.Alignment.RIGHT)
            .addColumn("Trend", TablePrinter.Alignment.LEFT);

        long totalClassesLatest = 0;
        long totalChunkLatest = 0;
        long totalBlockLatest = 0;
        long totalClassesFirst = 0;
        long totalChunkFirst = 0;
        long totalBlockFirst = 0;

        for (String type : sortedTypes) {
            AggregateRow latestRow = latest.getOrDefault(type, AggregateRow.ZERO);
            AggregateRow firstRow = first.getOrDefault(type, AggregateRow.ZERO);

            totalClassesLatest += latestRow.classes;
            totalChunkLatest += latestRow.chunkSz;
            totalBlockLatest += latestRow.blockSz;
            totalClassesFirst += firstRow.classes;
            totalChunkFirst += firstRow.chunkSz;
            totalBlockFirst += firstRow.blockSz;

            table.addRow(
                type,
                formatNumber(latestRow.classes),
                formatNumber(latestRow.chunkSz),
                formatNumber(latestRow.blockSz),
                hasTrend
                    ? formatTrend(latestRow.classes - firstRow.classes,
                        latestRow.chunkSz - firstRow.chunkSz,
                        latestRow.blockSz - firstRow.blockSz)
                    : "-"
            );
        }

        table.addRow(
            "Total",
            formatNumber(totalClassesLatest),
            formatNumber(totalChunkLatest),
            formatNumber(totalBlockLatest),
            hasTrend
                ? formatTrend(totalClassesLatest - totalClassesFirst,
                    totalChunkLatest - totalChunkFirst,
                    totalBlockLatest - totalBlockFirst)
                : "-"
        );

        return String.format("VM.classloader_stats (%d sample%s):\n%s",
            parsedSamples.size(),
            parsedSamples.size() == 1 ? "" : "s",
            table.render());
    }

    private Map<String, AggregateRow> parseByType(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }

        Map<String, AggregateRow> byType = new HashMap<>();
        for (String line : raw.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.matches("\\d+:")) {
                continue;
            }
            if (trimmed.startsWith("ClassLoader")) {
                continue;
            }

            Matcher matcher = ROW_PATTERN.matcher(trimmed);
            if (!matcher.matches()) {
                continue;
            }

            long classes = Long.parseLong(matcher.group(1));
            long chunkSz = Long.parseLong(matcher.group(2));
            long blockSz = Long.parseLong(matcher.group(3));
            String type = matcher.group(4).trim();

            AggregateRow previous = byType.getOrDefault(type, AggregateRow.ZERO);
            byType.put(type, new AggregateRow(
                previous.classes + classes,
                previous.chunkSz + chunkSz,
                previous.blockSz + blockSz
            ));
        }

        return byType;
    }

    private String formatTrend(long classesDelta, long chunkDelta, long blockDelta) {
        String arrow = classesDelta > 0 ? "↑" : classesDelta < 0 ? "↓" : "→";
        return String.format("%s %s cls, %s chunk, %s block",
            arrow,
            formatSigned(classesDelta),
            formatSigned(chunkDelta),
            formatSigned(blockDelta));
    }

    private String formatNumber(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private String formatSigned(long value) {
        return String.format(Locale.ROOT, "%+d", value);
    }

    private int getIntOption(Map<String, Object> options, String key, int defaultValue) {
        Object value = options.get(key);
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return defaultValue;
    }

    private long getLongOption(Map<String, Object> options, String key, long defaultValue) {
        Object value = options.get(key);
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return defaultValue;
    }

    private record AggregateRow(long classes, long chunkSz, long blockSz) {
        static final AggregateRow ZERO = new AggregateRow(0, 0, 0);
    }
}