package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.BaseAnalyzer;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.analyzer.impl.vmvitals.*;
import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jstall.provider.requirement.DataRequirements;

import java.util.*;

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
        return Set.of("top", "vital-rows", "unicode");
    }

    @Override
    public DumpRequirement dumpRequirement() {
        return DumpRequirement.ANY;
    }

    @Override
    public int defaultDumpCount() {
        return 1;
    }

    @Override
    public DataRequirements getDataRequirements(Map<String, Object> options) {
        int dumpCount = getIntOption(options, "dump-count", defaultDumpCount());
        long intervalMs = getLongOption(options, "interval", defaultIntervalMs());

        DataRequirements.Builder builder = DataRequirements.builder();

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
        boolean unicode = getBooleanOption(options, "unicode", true);
        String vmVitalsOutput = formatVmVitals(parsed, vitalRows, unicode);
        if (vmVitalsOutput.isEmpty()) {
            return AnalyzerResult.ok(formatRawFallback(rawVitals));
        }

        return AnalyzerResult.ok(unicode ? vmVitalsOutput : me.bechberger.jstall.util.render.Symbols.ASCII.sanitize(vmVitalsOutput));
    }

    /**
     * Formats VM vitals output with merged legend+trends header, then the data sections.
     */
    private String formatVmVitals(VmVitalsOutput vitals, int topN, boolean unicode) {
        if (vitals.sections().isEmpty()) {
            return "";
        }

        int extremesCap = Math.max(10, topN);

        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (VmVitalsSection s : vitals.sections()) {
            String section = formatSection(s, topN, extremesCap);
            if (!section.isEmpty()) {
                if (!first) sb.append("\n\n");
                sb.append(section);
                first = false;
            }
        }

        if (sb.isEmpty()) {
            return "";
        }

        String header = renderLegendWithTrends(vitals, unicode);
        return header.isEmpty() ? sb.toString() : header + "\n\n" + sb;
    }

    /**
     * Builds a unified header block with:
     *  - All metrics in one table (moving ones with ↑/↓/~/→, stable ones with = )
     *  - Observations section (threshold violations, growing metrics)
     * When unicode=false, uses ASCII arrow substitutes.
     */
    private String renderLegendWithTrends(VmVitalsOutput vitals, boolean unicode) {
        List<LegendEntry> entries = vitals.filteredLegend().entries();
        boolean hasTrend = vitals.hasTrendColumns();

        List<VmVitalsObservations.TrendEntry> allEntries = VmVitalsObservations.trendEntries(vitals, unicode);
        List<String> obs = VmVitalsObservations.observations(vitals, unicode);
        String timeRange = VmVitalsObservations.timeRange(vitals, unicode);

        if (entries.isEmpty() && !hasTrend && allEntries.isEmpty() && obs.isEmpty()) {
            return "";
        }

        // Arrow symbols — ASCII fallback for non-UTF-8 terminals
        String arrowUp    = unicode ? "↑"  : "^";
        String arrowDown  = unicode ? "↓"  : "v";
        String arrowRight = unicode ? "→"  : "->";
        String arrowStable = "=";
        String arrowFlaky = "~";
        String arrowUnknown = "?";
        String dashSep    = unicode ? " – " : " - ";

        StringBuilder sb = new StringBuilder();

        // --- Unified trends table ---
        List<VmVitalsObservations.TrendEntry> moving = allEntries.stream()
            .filter(t -> !t.stable()).toList();
        List<VmVitalsObservations.TrendEntry> stable = allEntries.stream()
            .filter(VmVitalsObservations.TrendEntry::stable).toList();

        // Build the display entries list: moving first, then stable
        List<VmVitalsObservations.TrendEntry> displayEntries = new ArrayList<>(moving);
        displayEntries.addAll(stable);

        if (!displayEntries.isEmpty() || hasTrend) {
            if (!displayEntries.isEmpty()) {
                int keyWidth = displayEntries.stream().mapToInt(t -> t.key().length()).max().orElse(0);
                int valWidth = displayEntries.stream()
                    .mapToInt(t -> Math.max(t.first().length(), t.last().length()))
                    .max().orElse(0);
                int deltaWidth = displayEntries.stream()
                    .mapToInt(t -> renderDelta(t, arrowUp, arrowDown, arrowRight, arrowFlaky, arrowUnknown, dashSep).trim().length())
                    .max().orElse(0);
                int descWidth = entries.isEmpty() ? 0 : displayEntries.stream()
                    .mapToInt(t -> cleanDesc(t.description()).length())
                    .max().orElse(0);

                String header = timeRange != null
                    ? String.format("Trends (%s):\n", timeRange.replace("→", arrowRight))
                    : "Trends:\n";
                sb.append(header);

                for (var t : displayEntries) {
                    String displayArrow = t.stable() ? arrowStable
                        : (t.arrow().equals("↑") || t.arrow().equals("^")) ? arrowUp
                        : (t.arrow().equals("↓") || t.arrow().equals("v")) ? arrowDown
                        : t.arrow().equals("~") ? arrowFlaky
                        : t.arrow().equals("?") ? arrowUnknown
                        : arrowRight;
                    String delta = renderDelta(t, arrowUp, arrowDown, arrowRight, arrowFlaky, arrowUnknown, dashSep);
                    String trendStr = String.format("%s %s %s  %s  %s",
                        padLeft(t.first(), valWidth), arrowRight, padLeft(t.last(), valWidth),
                        displayArrow, padLeft(delta.trim(), deltaWidth));
                    if (descWidth > 0) {
                        String desc = cleanDesc(t.description());
                        sb.append(String.format("  %-" + keyWidth + "s  %-" + descWidth + "s    %s\n",
                            t.key(), desc, trendStr));
                    } else {
                        sb.append(String.format("  %-" + keyWidth + "s  %s\n", t.key(), trendStr));
                    }
                }
            }

            if (hasTrend) {
                sb.append(String.format("  [trend]: prev %s current %s  ('?' = first sample, run again)\n",
                    arrowRight, unicode ? "Δ" : "d"));
            }
        }

        // --- Observations block ---
        if (!obs.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("Observations:\n");
            for (String o : obs) {
                sb.append("  * ").append(o).append("\n");
            }
        }

        return sb.toString().trim();
    }

    /** Renders the delta/range portion of a trend entry using the provided symbol set. */
    private static String renderDelta(VmVitalsObservations.TrendEntry t,
                                       String up, String down, String right,
                                       String flaky, String unknown, String dashSep) {
        String delta = t.delta();
        if (delta.isEmpty()) return "";
        // Replace unicode arrows inside delta strings (range notation uses – )
        return delta.replace("↑", up).replace("↓", down).replace("→", right)
                    .replace(" – ", dashSep).replace("–", dashSep.trim());
    }

    private static String cleanDesc(String description) {
        return description.replaceAll("\\s*\\[[a-z0-9]+]", "").trim();
    }

    private static String padLeft(String s, int width) {
        if (s.length() >= width) return s;
        return " ".repeat(width - s.length()) + s;
    }

    /**
     * Formats a single section: prints the section title, raw header lines, then the selected raw data lines.
     */
    private String formatSection(VmVitalsSection section, int topN, int extremesCap) {
        boolean extremesSection = isExtremesSection(section);
        int rowsToShow = extremesSection ? extremesCap : topN;

        List<String> rawLines = section.getTopRawLines(rowsToShow);
        if (rawLines.isEmpty()) {
            return "";
        }

        // For extremes: sort chronologically and de-duplicate rows with identical timestamps.
        // Timestamps are the fixed 19-char prefix of every data line.
        List<String> linesToPrint = rawLines;
        if (extremesSection) {
            LinkedHashMap<String, String> byTs = new LinkedHashMap<>();
            for (String line : rawLines) {
                String ts = line.length() >= 19 ? line.substring(0, 19) : line;
                byTs.putIfAbsent(ts, line);
            }
            linesToPrint = new ArrayList<>(byTs.values());
            linesToPrint.sort(Comparator.naturalOrder()); // timestamps sort lexicographically
        }

        StringBuilder sb = new StringBuilder();
        int total = section.dataRows().size();
        int shown = linesToPrint.size();
        if (extremesSection) {
            sb.append(String.format("%s — %d of %d:\n", normalizeSectionName(section.name()), shown, total));
        } else {
            sb.append(String.format("%s — %d of %d samples:\n", normalizeSectionName(section.name()), shown, total));
        }

        for (String headerLine : section.headerLines()) {
            sb.append(headerLine).append("\n");
        }
        for (String line : linesToPrint) {
            sb.append(line).append("\n");
        }

        // Strip trailing whitespace/newlines
        String result = sb.toString();
        int end = result.length();
        while (end > 0 && (result.charAt(end - 1) == '\n' || result.charAt(end - 1) == ' ')) end--;
        return result.substring(0, end);
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