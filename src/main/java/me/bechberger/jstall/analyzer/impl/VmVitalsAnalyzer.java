package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.BaseAnalyzer;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.analyzer.impl.vmvitals.*;
import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jstall.provider.requirement.DataRequirements;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Displays VM vitals information from VM.vitals jcmd command (SapMachine-specific).
 * <p>
 * Uses a robust data model to parse VM vitals output, then filters the legend
 * to only show entries relevant to the actual columns present in the data.
 * Shows the last n recent sample rows (configurable via --top option, default: 5)
 * followed by the "Samples at extremes" section capped at max(10, topN) rows.
 */
public class VmVitalsAnalyzer extends BaseAnalyzer {

    @Override
    public String name() {
        return "vm-vitals";
    }

    @Override
    public Set<String> supportedOptions() {
        return Set.of("top", "vital-rows");
    }

    @Override
    public DumpRequirement dumpRequirement() {
        return DumpRequirement.ANY;
    }

    @Override
    public DataRequirements getDataRequirements(Map<String, Object> options) {
        int dumpCount = getIntOption(options, "dump-count", defaultDumpCount());
        long intervalMs = getLongOption(options, "interval", defaultIntervalMs());

        DataRequirements.Builder builder = DataRequirements.builder()
            .addThreadDump();

        if (dumpCount > 1 && intervalMs > 0) {
            builder.addDeferredJcmdAtEnd("VM.vitals", null, dumpCount, intervalMs);
        } else {
            builder.addJcmdOnce("VM.vitals");
        }

        return builder.build();
    }

    @Override
    public AnalyzerResult analyze(ResolvedData data, Map<String, Object> options) {
        List<CollectedData> vitalsSamples = data.collectedData("vm-vitals");
        if (vitalsSamples.isEmpty()) {
            return AnalyzerResult.ok("""
                VM.vitals is not available on this JVM.
                It is a SapMachine-specific feature (https://sapmachine.io) that exposes detailed
                JVM counters sampled over time. On other JVMs, start with `jstall status`
                and, if you want memory detail, `jstall gc-heap-info` or `jstall vm-metaspace`.
                """.trim());
        }

        String rawVitals = vitalsSamples.get(vitalsSamples.size() - 1).rawData();
        if (rawVitals == null || rawVitals.isBlank()) {
            return AnalyzerResult.ok("VM.vitals returned no data for this JVM.");
        }

        VmVitalsOutput parsed = VmVitalsParser.parse(rawVitals);
        if (parsed == null) {
            return AnalyzerResult.ok(formatRawFallback(rawVitals));
        }

        int vitalRows = getIntOption(options, "vital-rows", getIntOption(options, "top", 10));
        String vmVitalsOutput = formatVmVitals(parsed, vitalRows);
        if (vmVitalsOutput.isEmpty()) {
            return AnalyzerResult.ok(formatRawFallback(rawVitals));
        }

        return AnalyzerResult.ok(vmVitalsOutput);
    }

    /**
     * Formats VM vitals output with merged legend+trends header, then the data sections.
     */
    private String formatVmVitals(VmVitalsOutput vitals, int topN) {
        if (vitals.sections().isEmpty()) {
            return "";
        }

        int extremesCap = Math.max(10, topN);
        String sections = vitals.sections().stream()
            .map(s -> formatSection(s, topN, extremesCap))
            .filter(s -> !s.isEmpty())
            .collect(Collectors.joining("\n\n"));

        if (sections.isEmpty()) {
            return "";
        }

        String header = renderLegendWithTrends(vitals);
        return header.isEmpty() ? sections : header + "\n\n" + sections;
    }

    /**
     * Builds a single table where each row shows: key, description, and (if available) first→last trend.
     * Followed by observations and footnotes.
     */
    private String renderLegendWithTrends(VmVitalsOutput vitals) {
        List<LegendEntry> entries = vitals.filteredLegend().entries();
        boolean hasDelta = vitals.filteredLegend().conditions().stream().anyMatch(c -> "delta".equals(c.tag()));
        boolean hasTrend = vitals.hasTrendColumns();

        List<VmVitalsObservations.TrendEntry> trends = VmVitalsObservations.trendEntries(vitals);
        List<String> obs = VmVitalsObservations.observations(vitals);
        String timeRange = VmVitalsObservations.timeRange(vitals);

        if (entries.isEmpty() && !hasDelta && !hasTrend && trends.isEmpty() && obs.isEmpty()) {
            return "";
        }

        // Build a map from legend key → trend entry for inline display
        Map<String, VmVitalsObservations.TrendEntry> trendByKey = new java.util.LinkedHashMap<>();
        for (var t : trends) trendByKey.put(t.key(), t);

        // Column widths — only consider entries that have a trend for desc alignment
        int keyWidth = entries.stream().mapToInt(e -> e.key().length()).max().orElse(0);
        int descWidth = entries.stream()
            .filter(e -> trendByKey.containsKey(e.key()))
            .mapToInt(e -> e.description().replaceAll("\\s*\\[[a-z0-9]+]", "").trim().length())
            .max().orElse(0);
        int valWidth = trends.stream()
            .mapToInt(t -> Math.max(t.first().length(), t.last().length()))
            .max().orElse(0);
        int deltaWidth = trends.stream().mapToInt(t -> t.delta().trim().length()).max().orElse(0);

        StringBuilder sb = new StringBuilder();
        if (timeRange != null) {
            sb.append(String.format("Legend & Trend (%s):\n", timeRange));
        } else {
            sb.append("Legend:\n");
        }

        for (LegendEntry entry : entries) {
            String desc = entry.description().replaceAll("\\s*\\[(?!delta)[a-z0-9]+]", "").trim();
            var t = trendByKey.get(entry.key());
            if (t != null) {
                String trendStr = String.format("%s → %s  %s  %s",
                    padLeft(t.first(), valWidth), padLeft(t.last(), valWidth),
                    t.arrow(), padLeft(t.delta().trim(), deltaWidth));
                sb.append(String.format("  %-" + keyWidth + "s  %-" + descWidth + "s    %s\n",
                    entry.key(), desc, trendStr));
            } else {
                sb.append(String.format("  %-" + keyWidth + "s  %s\n", entry.key(), desc));
            }
        }

        if (hasTrend) {
            sb.append("  [trend]: prev → current Δ  ('?' = first sample, run again)\n");
        }

        if (!obs.isEmpty()) {
            sb.append("\nObservations:\n");
            for (String o : obs) {
                sb.append("  * ").append(o).append("\n");
            }
        }

        return sb.toString().trim();
    }

    private static String padLeft(String s, int width) {
        if (s.length() >= width) return s;
        return " ".repeat(width - s.length()) + s;
    }

    /**
     * Formats a single section with headers and data rows.
     */
    private String formatSection(VmVitalsSection section, int topN, int extremesCap) {
        boolean extremesSection = isExtremesSection(section);
        int rowsToShow = extremesSection ? extremesCap : topN;
        List<DataRow> rowsToFormat = section.getTopRows(rowsToShow);

        if (rowsToFormat.isEmpty()) {
            return "";
        }

        // Extremes section: sort chronologically and merge rows sharing the same timestamp
        if (extremesSection) {
            rowsToFormat = rowsToFormat.stream()
                .sorted(Comparator.comparing(DataRow::timestamp))
                .toList();
            rowsToFormat = mergeByTimestamp(rowsToFormat);
        }

        StringBuilder sb = new StringBuilder();

        if (extremesSection) {
            sb.append(String.format("%s — %d of %d:\n",
                normalizeSectionName(section.name()), rowsToFormat.size(), section.dataRows().size()));
        } else {
            sb.append(String.format("%s — %d of %d samples:\n",
                normalizeSectionName(section.name()), rowsToFormat.size(), section.dataRows().size()));
        }

        for (String headerLine : section.headerLines()) {
            sb.append(headerLine).append("\n");
        }

        // Derive column widths from the column-name header line (last header line)
        List<Integer> colWidths = deriveColumnWidths(
                section.headerLines().isEmpty() ? "" : section.headerLines().get(section.headerLines().size() - 1),
                section.columnNames());

        for (DataRow row : rowsToFormat) {
            sb.append(formatDataRow(row, section.columnNames(), colWidths)).append("\n");
        }

        return sb.toString().trim();
    }

    private String formatDataRow(DataRow row, List<String> columnNames, List<Integer> colWidths) {
        StringBuilder sb = new StringBuilder();
        sb.append(row.timestamp().format(DataRow.TIMESTAMP_FORMATTER));
        sb.append("    ");

        List<String> valueList = row.valueList();
        for (int i = 0; i < columnNames.size(); i++) {
            String colName = columnNames.get(i);
            String value = i < valueList.size() ? valueList.get(i) : "";
            String marker = row.extremeMarkers().get(colName);

            int width = i < colWidths.size() ? colWidths.get(i) : value.length();
            // Right-align value within column width; marker (+/-) counts toward width
            int cellWidth = value.length() + (marker != null ? 1 : 0);
            if (cellWidth < width) {
                sb.append(" ".repeat(width - cellWidth));
            }
            sb.append(value);
            if (marker != null) sb.append(marker);

            if (i < columnNames.size() - 1) {
                sb.append("  ");
            }
        }

        // Strip trailing whitespace (empty trailing columns produce padding)
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') end--;
        return sb.substring(0, end);
    }

    /** Derives per-column display widths from the column-name header line. */
    private List<Integer> deriveColumnWidths(String headerLine, List<String> columnNames) {
        int dataStart = DataRow.TIMESTAMP_FORMATTER.format(java.time.LocalDateTime.now()).length() + 4;
        if (headerLine.length() <= dataStart) {
            return columnNames.stream().map(String::length).toList();
        }
        String dataPart = headerLine.substring(Math.min(dataStart, headerLine.length()));
        // Find start of each column token
        List<Integer> starts = new ArrayList<>();
        boolean inToken = false;
        for (int i = 0; i < dataPart.length(); i++) {
            if (!Character.isWhitespace(dataPart.charAt(i)) && !inToken) {
                starts.add(i);
                inToken = true;
            } else if (Character.isWhitespace(dataPart.charAt(i))) {
                inToken = false;
            }
        }
        List<Integer> widths = new ArrayList<>();
        for (int i = 0; i < columnNames.size(); i++) {
            if (i < starts.size()) {
                // End of this token = start of next token minus 2-space gap, or end of dataPart
                int tokenEnd = (i + 1 < starts.size()) ? starts.get(i + 1) - 2 : dataPart.length();
                int colWidth = tokenEnd - starts.get(i);
                widths.add(Math.max(colWidth, columnNames.get(i).length()));
            } else {
                widths.add(columnNames.get(i).length());
            }
        }
        return widths;
    }

    private List<DataRow> mergeByTimestamp(List<DataRow> rows) {
        List<DataRow> merged = new ArrayList<>();
        for (DataRow row : rows) {
            if (!merged.isEmpty() && merged.get(merged.size() - 1).timestamp().equals(row.timestamp())) {
                merged.set(merged.size() - 1, merged.get(merged.size() - 1).withMergedMarkers(row));
            } else {
                merged.add(row);
            }
        }
        return merged;
    }

    private boolean isExtremesSection(VmVitalsSection section) {        return section.name().toLowerCase(Locale.ROOT).contains("extremes");
    }

    private String normalizeSectionName(String sectionName) {
        String normalized = sectionName.endsWith(":") ? sectionName.substring(0, sectionName.length() - 1) : sectionName;
        if (normalized.startsWith("Samples at extremes")) {
            return "Samples at extremes";
        }
        return normalized;
    }

    private String formatRawFallback(String rawVitals) {
        return "VM.vitals output was returned but could not be parsed reliably. "
            + "Showing the raw output below so you can still inspect it:\n\n"
            + rawVitals.trim();
    }

}