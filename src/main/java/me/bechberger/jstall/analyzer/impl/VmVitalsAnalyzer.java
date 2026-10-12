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

    // Gap between columns in the rendered table (spaces)
    private static final String COL_GAP = "  ";

    /**
     * Formats a single section with headers and data rows.
     * Column widths are computed from the actual data so headers and cells are always aligned.
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

        List<String> columnNames = section.columnNames();

        // Compute column widths from data (value+marker may be one wider than value alone)
        List<Integer> colWidths = computeColWidths(columnNames, rowsToFormat);

        // Parse category groups from raw header lines (all lines except the last = category spans)
        List<List<Integer>> categoryGroups = parseCategoryGroups(section.headerLines(), columnNames);

        StringBuilder sb = new StringBuilder();

        if (extremesSection) {
            sb.append(String.format("%s — %d of %d:\n",
                normalizeSectionName(section.name()), rowsToFormat.size(), section.dataRows().size()));
        } else {
            sb.append(String.format("%s — %d of %d samples:\n",
                normalizeSectionName(section.name()), rowsToFormat.size(), section.dataRows().size()));
        }

        // Re-render category span headers (if any) aligned to computed widths
        String tsPrefix = " ".repeat(19 + COL_GAP.length());
        if (categoryGroups != null) {
            for (List<List<Integer>> tier : splitIntoTiers(section.headerLines(), categoryGroups)) {
                sb.append(tsPrefix).append(renderCategoryLine(tier, columnNames, colWidths)).append("\n");
            }
        }

        // Re-render the column-names header line
        sb.append(tsPrefix).append(renderColNamesLine(columnNames, colWidths)).append("\n");

        for (DataRow row : rowsToFormat) {
            sb.append(formatDataRow(row, columnNames, colWidths)).append("\n");
        }

        return sb.toString().trim();
    }

    /** Computes per-column widths as max(colName.length, max(value+marker length across all rows)). */
    private List<Integer> computeColWidths(List<String> columnNames, List<DataRow> rows) {
        List<Integer> widths = new ArrayList<>();
        for (int i = 0; i < columnNames.size(); i++) {
            int w = columnNames.get(i).length();
            for (DataRow row : rows) {
                List<String> vals = row.valueList();
                String v = i < vals.size() ? vals.get(i) : "";
                String marker = row.getExtremeMarker(i);
                int cellLen = v.length() + (marker != null ? 1 : 0);
                if (cellLen > w) w = cellLen;
            }
            widths.add(w);
        }
        return widths;
    }

    /** Renders a single column-names header line using computed widths. */
    private String renderColNamesLine(List<String> columnNames, List<Integer> colWidths) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columnNames.size(); i++) {
            String name = columnNames.get(i);
            int w = i < colWidths.size() ? colWidths.get(i) : name.length();
            // Right-align name within column width (matches right-aligned data values)
            if (name.length() < w) sb.append(" ".repeat(w - name.length()));
            sb.append(name);
            if (i < columnNames.size() - 1) sb.append(COL_GAP);
        }
        // Strip trailing spaces
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') end--;
        return sb.substring(0, end);
    }

    /**
     * Parses category groups from the raw header lines stored by the parser.
     * The last header line is the column-names line; everything before it is category/sub-header lines.
     * Returns null if there are no category lines.
     * Each element in the returned list is a list of column indices belonging to that category group,
     * ordered as they appear left-to-right.
     */
    private List<List<Integer>> parseCategoryGroups(List<String> headerLines, List<String> columnNames) {
        if (headerLines.size() < 2) return null; // only the column-names line, no categories

        String colNamesLine = headerLines.get(headerLines.size() - 1);
        // Find the data-portion start: first non-space character
        int dataStart = 0;
        while (dataStart < colNamesLine.length() && colNamesLine.charAt(dataStart) == ' ') dataStart++;

        // Find the start offset of each column name token in the raw line
        List<Integer> colStarts = new ArrayList<>();
        String dataPart = colNamesLine.substring(dataStart);
        boolean inToken = false;
        for (int i = 0; i < dataPart.length(); i++) {
            if (!Character.isWhitespace(dataPart.charAt(i)) && !inToken) {
                colStarts.add(dataStart + i);
                inToken = true;
            } else if (Character.isWhitespace(dataPart.charAt(i))) {
                inToken = false;
            }
        }

        // For each category header line (all but last), determine which columns it spans
        // by checking which column-start positions fall within each dash-label segment
        List<List<Integer>> groups = new ArrayList<>();
        // Process only the last category line (or handle multiple tiers by passing them through as-is)
        // Strategy: build groups from the first category-header line
        String catLine = headerLines.get(headerLines.size() - 2);
        String catData = catLine.length() > dataStart ? catLine.substring(dataStart) : "";

        // Find dash-label segments: contiguous non-space regions
        List<int[]> segments = new ArrayList<>(); // [startInData, endInData]
        boolean inSeg = false;
        int segStart = 0;
        for (int i = 0; i <= catData.length(); i++) {
            boolean space = (i == catData.length() || catData.charAt(i) == ' ');
            if (!space && !inSeg) { segStart = i; inSeg = true; }
            else if (space && inSeg) { segments.add(new int[]{segStart, i}); inSeg = false; }
        }

        for (int si = 0; si < segments.size(); si++) {
            int segStartAbs = dataStart + segments.get(si)[0];
            // Each segment "owns" up to (but not including) the start of the next segment,
            // so columns that start exactly at the end of a segment boundary are still captured.
            int nextSegStart = si + 1 < segments.size()
                ? dataStart + segments.get(si + 1)[0]
                : Integer.MAX_VALUE;
            List<Integer> group = new ArrayList<>();
            for (int ci = 0; ci < colStarts.size(); ci++) {
                if (colStarts.get(ci) >= segStartAbs && colStarts.get(ci) < nextSegStart) {
                    group.add(ci);
                }
            }
            if (!group.isEmpty()) groups.add(group);
        }

        return groups.isEmpty() ? null : groups;
    }

    /**
     * Returns one tier of category groups per non-column-names header line.
     * Each tier is independently parsed from the corresponding raw header line.
     */
    private List<List<List<Integer>>> splitIntoTiers(List<String> headerLines, List<List<Integer>> groups) {
        if (headerLines.size() <= 2) {
            // Only one category line: return the already-parsed groups
            return List.of(groups);
        }
        // Multiple category lines: re-parse each one independently
        // headerLines[-1] = column names; headerLines[0..n-2] = category lines (outermost first)
        String colNamesLine = headerLines.get(headerLines.size() - 1);
        int dataStart = 0;
        while (dataStart < colNamesLine.length() && colNamesLine.charAt(dataStart) == ' ') dataStart++;

        // Build column starts once
        List<Integer> colStarts = new ArrayList<>();
        String dataPart = colNamesLine.substring(dataStart);
        boolean inTok = false;
        for (int i = 0; i < dataPart.length(); i++) {
            if (!Character.isWhitespace(dataPart.charAt(i)) && !inTok) {
                colStarts.add(dataStart + i);
                inTok = true;
            } else if (Character.isWhitespace(dataPart.charAt(i))) {
                inTok = false;
            }
        }

        List<List<List<Integer>>> tiers = new ArrayList<>();
        for (int li = 0; li < headerLines.size() - 1; li++) {
            String catLine = headerLines.get(li);
            String catData = catLine.length() > dataStart ? catLine.substring(dataStart) : "";

            List<int[]> segments = new ArrayList<>();
            boolean inSeg = false;
            int segStart = 0;
            for (int i = 0; i <= catData.length(); i++) {
                boolean space = (i == catData.length() || catData.charAt(i) == ' ');
                if (!space && !inSeg) { segStart = i; inSeg = true; }
                else if (space && inSeg) { segments.add(new int[]{segStart, i}); inSeg = false; }
            }

            List<List<Integer>> tierGroups = new ArrayList<>();
            for (int si = 0; si < segments.size(); si++) {
                int segStartAbs = dataStart + segments.get(si)[0];
                int nextSegStart = si + 1 < segments.size()
                    ? dataStart + segments.get(si + 1)[0]
                    : Integer.MAX_VALUE;
                List<Integer> group = new ArrayList<>();
                for (int ci = 0; ci < colStarts.size(); ci++) {
                    if (colStarts.get(ci) >= segStartAbs && colStarts.get(ci) < nextSegStart) {
                        group.add(ci);
                    }
                }
                if (!group.isEmpty()) tierGroups.add(group);
            }
            if (!tierGroups.isEmpty()) tiers.add(tierGroups);
        }
        return tiers.isEmpty() ? List.of(groups) : tiers;
    }

    /** Renders a category span line for a set of column groups. */
    private String renderCategoryLine(List<List<Integer>> groups, List<String> columnNames, List<Integer> colWidths) {
        if (groups == null || groups.isEmpty()) return "";

        // Build the full column-line width so we know total span widths.
        // Each group spans: sum(colWidths[i] for i in group) + (group.size()-1)*COL_GAP.length()
        // Between groups, we need: COL_GAP + sum of widths of any ungrouped columns in between + COL_GAP per ungrouped col
        StringBuilder sb = new StringBuilder();
        for (int gi = 0; gi < groups.size(); gi++) {
            List<Integer> group = groups.get(gi);
            int groupWidth = 0;
            for (int ci = 0; ci < group.size(); ci++) {
                int idx = group.get(ci);
                groupWidth += idx < colWidths.size() ? colWidths.get(idx) : columnNames.get(idx).length();
                if (ci < group.size() - 1) groupWidth += COL_GAP.length();
            }
            sb.append(centerInDashes(groupLabel(group, columnNames), groupWidth));

            if (gi < groups.size() - 1) {
                // Gap between this group and the next: COL_GAP always, plus width of each ungrouped column
                int lastInGroup = group.get(group.size() - 1);
                int firstInNext = groups.get(gi + 1).get(0);
                sb.append(COL_GAP);
                for (int ci = lastInGroup + 1; ci < firstInNext; ci++) {
                    int w = ci < colWidths.size() ? colWidths.get(ci) : columnNames.get(ci).length();
                    sb.append(" ".repeat(w)).append(COL_GAP);
                }
            }
        }
        // Strip trailing spaces
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') end--;
        return sb.substring(0, end);
    }

    /**
     * Derives a label for a category group from its column names.
     * For example, columns [heap-comm, heap-used] → "heap", [cpu-us, cpu-sy, cpu-id] → "cpu".
     */
    private String groupLabel(List<Integer> colIndices, List<String> columnNames) {
        if (colIndices.isEmpty()) return "";
        List<String> names = colIndices.stream().map(columnNames::get).toList();
        // Find longest common prefix (up to the last '-' boundary)
        String first = names.get(0);
        String prefix = first;
        for (String n : names) {
            while (!n.startsWith(prefix)) {
                int dash = prefix.lastIndexOf('-');
                prefix = dash > 0 ? prefix.substring(0, dash) : "";
                if (prefix.isEmpty()) break;
            }
        }
        return prefix.isEmpty() ? "" : prefix.replaceAll("-$", "");
    }

    /** Centers a label in a field of dashes: "---label---". */
    private String centerInDashes(String label, int width) {
        if (label.isEmpty() || label.length() + 2 >= width) {
            return "-".repeat(width);
        }
        String inner = "-" + label + "-";
        int dashes = width - inner.length();
        int left = dashes / 2;
        int right = dashes - left;
        return "-".repeat(left) + inner + "-".repeat(right);
    }

    private String formatDataRow(DataRow row, List<String> columnNames, List<Integer> colWidths) {
        StringBuilder sb = new StringBuilder();
        sb.append(row.timestamp().format(DataRow.TIMESTAMP_FORMATTER));
        sb.append(COL_GAP);

        List<String> valueList = row.valueList();
        for (int i = 0; i < columnNames.size(); i++) {
            String value = i < valueList.size() ? valueList.get(i) : "";
            String marker = row.getExtremeMarker(i);

            int width = i < colWidths.size() ? colWidths.get(i) : value.length();
            // Right-align value within column width; marker (+/-) counts toward width
            int cellWidth = value.length() + (marker != null ? 1 : 0);
            if (cellWidth < width) {
                sb.append(" ".repeat(width - cellWidth));
            }
            sb.append(value);
            if (marker != null) sb.append(marker);

            if (i < columnNames.size() - 1) {
                sb.append(COL_GAP);
            }
        }

        // Strip trailing whitespace
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') end--;
        return sb.substring(0, end);
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