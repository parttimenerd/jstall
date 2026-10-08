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
        return Set.of("top");
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

        int top = getIntOption(options, "top", 5);
        String vmVitalsOutput = formatVmVitals(parsed, top);
        if (vmVitalsOutput.isEmpty()) {
            return AnalyzerResult.ok(formatRawFallback(rawVitals));
        }

        return AnalyzerResult.ok(vmVitalsOutput);
    }

    /**
     * Formats VM vitals output with filtered legend + top recent samples + extremes.
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

        String legend = renderFilteredLegend(vitals.filteredLegend());
        String body = legend.isEmpty() ? sections : legend + "\n\n" + sections;

        String observations = VmVitalsObservations.analyze(vitals);
        return observations.isEmpty() ? body : body + "\n\n" + observations;
    }

    private String renderFilteredLegend(VmVitalsLegend filteredLegend) {
        if (filteredLegend.entries().isEmpty()) {
            return "";
        }

        List<LegendEntry> entries = filteredLegend.entries();
        List<String> lines = new ArrayList<>();
        for (LegendEntry entry : entries) {
            lines.add(String.format("%15s: %s", entry.key(), entry.description().replaceAll("\\s*\\[[a-z0-9]+]", "").trim()));
        }

        // Two-column layout: pair entries side-by-side
        int colWidth = lines.stream().mapToInt(String::length).max().orElse(40);
        StringBuilder sb = new StringBuilder();
        sb.append("VM.vitals legend:\n\n");
        for (int i = 0; i < lines.size(); i += 2) {
            if (i + 1 < lines.size()) {
                sb.append(String.format("%-" + colWidth + "s    %s\n", lines.get(i), lines.get(i + 1)));
            } else {
                sb.append(lines.get(i)).append("\n");
            }
        }

        boolean hasDelta = filteredLegend.conditions().stream().anyMatch(c -> "delta".equals(c.tag()));
        if (hasDelta) {
            sb.append("\n  [delta]: values refer to the previous measurement.\n");
        }

        return sb.toString().trim();
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