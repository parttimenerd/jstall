package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jstall.provider.requirement.DataRequirements;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Displays VM vitals information from VM.vitals jcmd command (SapMachine-specific).
 * <p>
 * Shows the last n recent sample rows (configurable via --top option, default: 5)
 * followed by the "Samples at extremes" section capped at max(10, topN) rows.
 */
public class VmVitalsAnalyzer implements Analyzer {

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
        return DataRequirements.builder()
            .addThreadDump()
            .addJcmdOnce("VM.vitals")
            .build();
    }

    @Override
    public AnalyzerResult analyze(ResolvedData data, Map<String, Object> options) {
        List<CollectedData> vitalsSamples = data.collectedData("vm-vitals");
        if (vitalsSamples.isEmpty()) {
            return AnalyzerResult.ok("VM.vitals not available (requires SapMachine JVM)");
        }

        String rawVitals = vitalsSamples.get(vitalsSamples.size() - 1).rawData();
        if (rawVitals == null || rawVitals.isBlank()) {
            return AnalyzerResult.nothing();
        }

        int top = getIntOption(options, "top", 5);
        int extremesCap = Math.max(10, top);
        String vmVitalsOutput = formatVmVitals(rawVitals, top, extremesCap);
        if (vmVitalsOutput.isEmpty()) {
            return AnalyzerResult.nothing();
        }

        return AnalyzerResult.ok(vmVitalsOutput);
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

    private String formatVmVitals(String rawVitals, int topN, int extremesCap) {
        if (rawVitals == null || rawVitals.isBlank()) {
            return "";
        }

        String[] lines = rawVitals.split("\\r?\\n");

        // Split into two sections at the "Samples at extremes" boundary
        int extremesStart = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("Samples at extremes")) {
                extremesStart = i;
                break;
            }
        }

        int recentEnd = extremesStart >= 0 ? extremesStart : lines.length;
        String[] recentLines = java.util.Arrays.copyOfRange(lines, 0, recentEnd);
        String[] extremesLines = extremesStart >= 0
                ? java.util.Arrays.copyOfRange(lines, extremesStart, lines.length)
                : new String[0];

        String recentSection = formatSection(recentLines, topN, "Recent samples (last " + topN + ")");
        String extremesSection = formatSection(extremesLines, extremesCap, "Samples at extremes (since start)");

        if (recentSection.isEmpty() && extremesSection.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("VM Vitals:\n");
        if (!recentSection.isEmpty()) {
            sb.append(recentSection).append("\n");
        }
        if (!extremesSection.isEmpty()) {
            if (!recentSection.isEmpty()) {
                sb.append("\n");
            }
            sb.append(extremesSection).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Formats one section (recent or extremes) of VM.vitals output.
     * Finds the 3-line header block ending with the "comm used" column line,
     * then collects all data rows (lines starting with a date).
     * If topN >= 0, only the last topN data rows are shown.
     */
    private String formatSection(String[] lines, int topN, String sectionLabel) {
        List<String> headerLines = new ArrayList<>();
        List<String> dataLines = new ArrayList<>();
        int columnHeaderIdx = -1;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (columnHeaderIdx < 0 && trimmed.contains("comm") && trimmed.contains("used")) {
                columnHeaderIdx = i;
                // Collect up to 2 non-empty lines immediately before as grouped headers
                List<String> before = new ArrayList<>();
                for (int j = i - 1; j >= 0 && before.size() < 2; j--) {
                    if (!lines[j].trim().isEmpty()) {
                        before.add(0, lines[j]);
                    } else {
                        break;
                    }
                }
                headerLines.addAll(before);
                headerLines.add(lines[i]);
                continue;
            }
            if (columnHeaderIdx >= 0 && trimmed.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
                dataLines.add(lines[i]);
            }
        }

        if (dataLines.isEmpty()) {
            return "";
        }

        List<String> rows = topN >= 0 ? dataLines.subList(Math.max(0, dataLines.size() - topN), dataLines.size()) : dataLines;

        StringBuilder sb = new StringBuilder();
        sb.append(sectionLabel).append(":\n");
        for (String h : headerLines) {
            sb.append(h).append("\n");
        }
        for (String row : rows) {
            sb.append(row).append("\n");
        }
        return sb.toString().trim();
    }

}