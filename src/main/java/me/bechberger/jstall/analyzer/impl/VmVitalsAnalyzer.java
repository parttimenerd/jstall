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
                It is currently exposed by SapMachine. On other JVMs, start with `jstall status`
                and, if you want memory detail, `jstall gc-heap-info` or `jstall vm-metaspace`.
                """.trim());
        }

        String rawVitals = vitalsSamples.get(vitalsSamples.size() - 1).rawData();
        if (rawVitals == null || rawVitals.isBlank()) {
            return AnalyzerResult.nothing();
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
            .collect(Collectors.joining("\n"));

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

        StringBuilder sb = new StringBuilder();
        sb.append("VM.vitals legend (filtered by active columns shown below):\n\n");

        for (LegendEntry entry : filteredLegend.entries()) {
            sb.append(String.format("%15s: %s\n", entry.key(), entry.description().replaceAll("\\s*\\[[a-z0-9]+]", "").trim()));
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

        StringBuilder sb = new StringBuilder();

        String suffix = extremesSection ? "marked samples since start" : "samples, newest last";
        sb.append(String.format("%s (showing %d of %d %s):\n",
            normalizeSectionName(section.name()), rowsToFormat.size(), section.dataRows().size(), suffix));

        for (String headerLine : section.headerLines()) {
            sb.append(headerLine).append("\n");
        }

        for (DataRow row : rowsToFormat) {
            sb.append(formatDataRow(row, section.columnNames())).append("\n");
        }

        return sb.toString().trim();
    }

    private String formatDataRow(DataRow row, List<String> columnNames) {
        StringBuilder sb = new StringBuilder();
        sb.append(row.timestamp().format(DataRow.TIMESTAMP_FORMATTER));
        sb.append("    ");

        List<String> valueList = row.valueList();
        for (int i = 0; i < columnNames.size(); i++) {
            String colName = columnNames.get(i);
            String value = i < valueList.size() ? valueList.get(i) : "";
            String marker = row.extremeMarkers().get(colName);

            sb.append(marker != null ? value + marker : value);

            if (i < columnNames.size() - 1) {
                sb.append("  ");
            }
        }

        return sb.toString();
    }

    private boolean isExtremesSection(VmVitalsSection section) {
        return section.name().toLowerCase(Locale.ROOT).contains("extremes");
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