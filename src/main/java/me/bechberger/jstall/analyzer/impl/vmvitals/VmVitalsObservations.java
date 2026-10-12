package me.bechberger.jstall.analyzer.impl.vmvitals;

import me.bechberger.jstall.analyzer.Cell;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Derives trend summaries and flagged observations from parsed VM.vitals data.
 * Uses the first "Last N" section (not "Samples at extremes") and compares
 * the earliest to the latest row to surface growing metrics and threshold violations.
 */
public class VmVitalsObservations {

    private VmVitalsObservations() {}

    /** A single non-stable trend row: key, formatted first/last values, direction arrow, delta string. */
    public record TrendEntry(String key, String first, String last, String arrow, String delta) {}

    /**
     * Returns per-column trend entries (only columns with movement — stable columns are omitted).
     * Empty list when there are fewer than 2 samples.
     */
    public static List<TrendEntry> trendEntries(VmVitalsOutput vitals) {
        VmVitalsSection section = findMainSection(vitals);
        if (section == null || section.dataRows().size() < 2) {
            return List.of();
        }
        return buildTrendEntries(section, vitals.filteredLegend().entries());
    }

    /**
     * Returns flagged observation strings, or empty list when nothing notable.
     */
    public static List<String> observations(VmVitalsOutput vitals) {
        VmVitalsSection section = findMainSection(vitals);
        if (section == null || section.dataRows().size() < 2) {
            return List.of();
        }
        List<LegendEntry> legend = vitals.filteredLegend().entries();
        DataRow first = section.dataRows().stream()
                .min(java.util.Comparator.comparing(DataRow::timestamp)).orElseThrow();
        DataRow latest = section.dataRows().stream()
                .max(java.util.Comparator.comparing(DataRow::timestamp)).orElseThrow();
        return buildObservations(legend, section.columnNames(), first, latest);
    }

    /**
     * Returns sample count and "HH:mm → HH:mm" time range for the main section, or null if fewer than 2 samples.
     */
    public static String timeRange(VmVitalsOutput vitals) {
        VmVitalsSection section = findMainSection(vitals);
        if (section == null || section.dataRows().size() < 2) {
            return null;
        }
        DataRow first = section.dataRows().stream()
                .min(java.util.Comparator.comparing(DataRow::timestamp)).orElseThrow();
        DataRow latest = section.dataRows().stream()
                .max(java.util.Comparator.comparing(DataRow::timestamp)).orElseThrow();
        String range = first.timestamp().format(DataRow.TIMESTAMP_FORMATTER).substring(11, 16)
                + " → " + latest.timestamp().format(DataRow.TIMESTAMP_FORMATTER).substring(11, 16);
        return section.dataRows().size() + " samples, " + range;
    }

    /**
     * Returns a formatted trend + observations block, or empty string if nothing notable.
     * @deprecated Use {@link #trendEntries} and {@link #observations} directly.
     */
    public static String analyze(VmVitalsOutput vitals) {
        List<TrendEntry> trends = trendEntries(vitals);
        List<String> obs = observations(vitals);
        String range = timeRange(vitals);

        if (trends.isEmpty() && obs.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        if (!trends.isEmpty() && range != null) {
            sb.append(String.format("Trends (%s):\n", range));
            int keyWidth = trends.stream().mapToInt(r -> r.key().length()).max().orElse(8);
            int valWidth = trends.stream()
                    .mapToInt(r -> Math.max(r.first().length(), r.last().length()))
                    .max().orElse(6);
            for (TrendEntry t : trends) {
                sb.append(String.format("  %-" + keyWidth + "s  %s → %s  %s%s\n",
                        t.key(), padLeft(t.first(), valWidth), padLeft(t.last(), valWidth),
                        t.arrow(), t.delta()));
            }
        }
        if (!obs.isEmpty()) {
            if (!trends.isEmpty()) sb.append("\n");
            sb.append("Observations:\n");
            for (String o : obs) {
                sb.append("  * ").append(o).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private static VmVitalsSection findMainSection(VmVitalsOutput vitals) {
        for (VmVitalsSection s : vitals.sections()) {
            String name = s.name().toLowerCase(Locale.ROOT);
            if ((name.startsWith("last") || name.startsWith("now")) && !name.contains("extremes")) {
                return s;
            }
        }
        return null;
    }

    private static List<TrendEntry> buildTrendEntries(VmVitalsSection section, List<LegendEntry> legend) {
        record TrendRow(String key, ParsedValue firstVal, ParsedValue latestVal,
                        boolean flaky, long minBytes, long maxBytes) {}
        List<TrendRow> rows = new ArrayList<>();

        // Sort rows oldest→newest for consistent direction
        List<DataRow> allRows = section.dataRows().stream()
                .sorted(java.util.Comparator.comparing(DataRow::timestamp))
                .toList();
        List<String> colNames = section.columnNames();
        for (int i = 0; i < colNames.size(); i++) {
            if (isDeltaColumn(legend, colNames, i)) {
                continue;
            }

            // Use first/last *available* value per column to avoid ? from incomplete rows
            ParsedValue fv = ParsedValue.parse(null);
            ParsedValue lv = ParsedValue.parse(null);
            for (DataRow r : allRows) {
                ParsedValue pv = parseAt(r, i);
                if (pv.isAvailable()) { if (!fv.isAvailable()) fv = pv; lv = pv; }
            }

            if (!fv.isAvailable() && !lv.isAvailable()) {
                continue;
            }
            String key = i < legend.size() ? legend.get(i).key() : colNames.get(i);

            // Compute reversal count and value range across all rows
            boolean flaky = false;
            long minBytes = Long.MAX_VALUE, maxBytes = Long.MIN_VALUE;
            if (allRows.size() >= 3) {
                int reversals = 0, transitions = 0;
                long prev = Long.MIN_VALUE;
                int prevDir = 0;
                for (DataRow r : allRows) {
                    ParsedValue pv = parseAt(r, i);
                    if (!pv.isAvailable()) continue;
                    long val = pv.getBytes();
                    minBytes = Math.min(minBytes, val);
                    maxBytes = Math.max(maxBytes, val);
                    if (prev != Long.MIN_VALUE) {
                        int dir = Long.compare(val, prev);
                        if (dir != 0) {
                            if (prevDir != 0 && dir != prevDir) reversals++;
                            transitions++;
                            prevDir = dir;
                        }
                    }
                    prev = val;
                }
                // Flaky: more than 30% of non-flat transitions reverse direction,
                // AND range is significant (≥5% relative for byte values, ≥5 absolute for plain integers)
                boolean enoughRange = maxBytes > 0 && (maxBytes - minBytes) * 100 / maxBytes >= 5;
                if (fv.isAvailable() && fv.original() != null) {
                    String orig = fv.original().trim();
                    boolean isInteger = !orig.isEmpty() && Character.isDigit(orig.charAt(orig.length() - 1));
                    if (isInteger) enoughRange = (maxBytes - minBytes) >= 5;
                }
                flaky = transitions >= 3 && reversals * 100 / transitions > 30 && enoughRange;
            }
            if (minBytes == Long.MAX_VALUE) { minBytes = 0; maxBytes = 0; }

            rows.add(new TrendRow(key, fv, lv, flaky, minBytes, maxBytes));
        }

        if (rows.isEmpty()) {
            return List.of();
        }

        // Compute arrows; collect only rows with movement
        List<TrendEntry> result = new ArrayList<>();
        for (TrendRow row : rows) {
            String fStr = fmtVal(row.firstVal());
            String lStr = fmtVal(row.latestVal());
            String deltaStr = "";
            String arrow;

            if (row.firstVal().isAvailable() && row.latestVal().isAvailable()) {
                if (row.flaky()) {
                    arrow = "~";
                    deltaStr = " (range: " + fmtDelta(row.latestVal(), row.minBytes())
                             + " – " + fmtDelta(row.latestVal(), row.maxBytes()) + ")";
                } else {
                    long delta = row.latestVal().getBytes() - row.firstVal().getBytes();
                    if (delta > 0) {
                        String orig = row.firstVal().original() != null ? row.firstVal().original().trim() : "";
                        boolean isInteger = !orig.isEmpty() && Character.isDigit(orig.charAt(orig.length() - 1));
                        boolean noise = isInteger
                                ? delta < 2
                                : row.firstVal().getBytes() > 0 && delta * 200 / row.firstVal().getBytes() < 1;
                        if (noise) { arrow = "→"; }
                        else { arrow = "↑"; deltaStr = " +" + fmtDelta(row.latestVal(), delta); }
                    } else if (delta < 0) {
                        String orig = row.firstVal().original() != null ? row.firstVal().original().trim() : "";
                        boolean isInteger = !orig.isEmpty() && Character.isDigit(orig.charAt(orig.length() - 1));
                        boolean noise = isInteger
                                ? -delta < 2
                                : row.firstVal().getBytes() > 0 && (-delta) * 200 / row.firstVal().getBytes() < 1;
                        if (noise) { arrow = "→"; }
                        else { arrow = "↓"; deltaStr = " -" + fmtDelta(row.latestVal(), -delta); }
                    } else {
                        arrow = "→";
                    }
                }
            } else {
                arrow = "?";
            }
            if (!arrow.equals("→")) {
                result.add(new TrendEntry(row.key(), fStr, lStr, arrow, deltaStr));
            }
        }
        return result;
    }

    private static boolean isDeltaColumn(List<LegendEntry> legend, List<String> colNames, int i) {
        if (i < legend.size() && legend.get(i).conditionTags().contains("delta")) {
            return true;
        }
        // Fallback: known short names for delta columns
        String name = colNames.get(i);
        return name.equals("cr") || name.equals("ld") || name.equals("uld")
                || name.equals("si") || name.equals("so");
    }

    private static List<String> buildObservations(List<LegendEntry> legend, List<String> colNames,
                                                   DataRow first, DataRow latest) {
        List<String> obs = new ArrayList<>();

        ParsedValue heapUsed = findValue(legend, colNames, latest, "heap-used", "used", 1);
        ParsedValue heapComm = findValue(legend, colNames, latest, "heap-comm", "comm", 0);
        ParsedValue heapUsedFirst = findValue(legend, colNames, first, "heap-used", "used", 1);

        // Heap pressure
        if (heapUsed.isAvailable() && heapComm.isAvailable() && heapComm.getBytes() > 0) {
            int pct = (int) (100L * heapUsed.getBytes() / heapComm.getBytes());
            if (pct >= 80) {
                obs.add(String.format("heap used is %d%% of committed (%s / %s) — high memory pressure, consider -Xmx",
                        pct, Cell.formatBytes(heapUsed.getBytes()), Cell.formatBytes(heapComm.getBytes())));
            }
        }

        // Heap growing > 5% relative
        if (heapUsed.isAvailable() && heapUsedFirst.isAvailable() && heapUsedFirst.getBytes() > 0) {
            long delta = heapUsed.getBytes() - heapUsedFirst.getBytes();
            int pct = (int) (100L * delta / heapUsedFirst.getBytes());
            if (pct >= 5) {
                obs.add(String.format("heap-used growing ↑ %s over window — possible allocation pressure or memory leak",
                        Cell.formatBytes(delta)));
            }
        }

        // Metaspace growing
        ParsedValue metaUsed = findValue(legend, colNames, latest, "meta-used", "used", 3);
        ParsedValue metaUsedFirst = findValue(legend, colNames, first, "meta-used", "used", 3);
        if (metaUsed.isAvailable() && metaUsedFirst.isAvailable()) {
            long delta = metaUsed.getBytes() - metaUsedFirst.getBytes();
            if (delta > 0) {
                obs.add(String.format("metaspace growing ↑ %s over window — possible class leak", Cell.formatBytes(delta)));
            }
        }

        // Metaspace near GC threshold
        ParsedValue metaGctr = findValue(legend, colNames, latest, "meta-gctr", "gctr", -1);
        if (metaUsed.isAvailable() && metaGctr.isAvailable() && metaGctr.getBytes() > 0) {
            int pct = (int) (100L * metaUsed.getBytes() / metaGctr.getBytes());
            if (pct >= 75) {
                obs.add(String.format("metaspace used is %d%% of GC threshold (%s / %s)",
                        pct, Cell.formatBytes(metaUsed.getBytes()), Cell.formatBytes(metaGctr.getBytes())));
            }
        }

        // Thread count growing
        ParsedValue jthrNum = findValue(legend, colNames, latest, "jthr-num", "num", -1);
        ParsedValue jthrNumFirst = findValue(legend, colNames, first, "jthr-num", "num", -1);
        if (jthrNum.isAvailable() && jthrNumFirst.isAvailable()) {
            long delta = jthrNum.getBytes() - jthrNumFirst.getBytes();
            if (delta >= 5) {
                obs.add(String.format("thread count grew +%d (from %d to %d) — possible thread leak",
                        delta, jthrNumFirst.getBytes(), jthrNum.getBytes()));
            }
        }

        // Class count growing
        ParsedValue clsNum = findValue(legend, colNames, latest, "cls-num", "num", -1);
        ParsedValue clsNumFirst = findValue(legend, colNames, first, "cls-num", "num", -1);
        if (clsNum.isAvailable() && clsNumFirst.isAvailable()) {
            long delta = clsNum.getBytes() - clsNumFirst.getBytes();
            if (delta >= 50) {
                obs.add(String.format("loaded class count grew +%d (from %d to %d) — possible classloader leak",
                        delta, clsNumFirst.getBytes(), clsNum.getBytes()));
            }
        }

        // Code cache growing > 20%
        ParsedValue codeLatest = findValue(legend, colNames, latest, "code", "code", -1);
        ParsedValue codeFirst = findValue(legend, colNames, first, "code", "code", -1);
        if (codeLatest.isAvailable() && codeFirst.isAvailable() && codeFirst.getBytes() > 0) {
            long delta = codeLatest.getBytes() - codeFirst.getBytes();
            int pct = (int) (100L * delta / codeFirst.getBytes());
            if (pct >= 20) {
                obs.add(String.format("code cache growing ↑ %s (+%d%% over window) — sustained JIT compilation activity",
                        Cell.formatBytes(delta), pct));
            }
        }

        // High CPU steal (system columns)
        ParsedValue cpuSt = findValue(legend, colNames, latest, "cpu-st", "cpu-st", -1);
        if (cpuSt.isAvailable() && cpuSt.getBytes() > 10) {
            obs.add(String.format("high CPU steal (%d%%) — host may be over-committed", cpuSt.getBytes()));
        }

        // Swap growing
        ParsedValue swapLatest = findValue(legend, colNames, latest, "swap", "swap", -1);
        ParsedValue swapFirst = findValue(legend, colNames, first, "swap", "swap", -1);
        if (swapLatest.isAvailable() && swapFirst.isAvailable()) {
            long delta = swapLatest.getBytes() - swapFirst.getBytes();
            if (delta > 0) {
                obs.add(String.format("swap usage growing ↑ %s — memory pressure at OS level", Cell.formatBytes(delta)));
            }
        }

        // RSS growing (process columns)
        ParsedValue rssLatest = findValue(legend, colNames, latest, "rss-all", "rss-all", -1);
        ParsedValue rssFirst = findValue(legend, colNames, first, "rss-all", "rss-all", -1);
        if (rssLatest.isAvailable() && rssFirst.isAvailable()) {
            long delta = rssLatest.getBytes() - rssFirst.getBytes();
            if (delta > 0) {
                obs.add(String.format("RSS growing ↑ %s — check for native memory leak", Cell.formatBytes(delta)));
            }
        }

        return obs;
    }

    /**
     * Finds a column value by legend key first, then by short name in colNames, then by positional hint.
     * legendKey: full qualified key e.g. "heap-used"
     * shortName: fallback short column name e.g. "used"
     * posHint: positional hint for the Nth occurrence of shortName (-1 = use first occurrence)
     */
    private static ParsedValue findValue(List<LegendEntry> legend, List<String> colNames,
                                          DataRow row, String legendKey, String shortName, int posHint) {
        // 1. Try legend key match
        for (int i = 0; i < legend.size(); i++) {
            if (legendKey.equals(legend.get(i).key())) {
                return parseAt(row, i);
            }
        }
        // 2. Try short name by positional hint (posHint = expected index in colNames)
        if (posHint >= 0 && posHint < colNames.size() && shortName.equals(colNames.get(posHint))) {
            return parseAt(row, posHint);
        }
        // 3. Try first occurrence of shortName in colNames
        for (int i = 0; i < colNames.size(); i++) {
            if (shortName.equals(colNames.get(i))) {
                return parseAt(row, i);
            }
        }
        return ParsedValue.parse(null);
    }

    private static ParsedValue parseAt(DataRow row, int index) {
        List<String> vl = row.valueList();
        if (index >= vl.size()) {
            return ParsedValue.parse(null);
        }
        return ParsedValue.parse(vl.get(index));
    }

    private static String fmtVal(ParsedValue pv) {
        if (!pv.isAvailable()) {
            return "?";
        }
        // If the original string has no unit suffix, display as a plain integer
        String orig = pv.original() != null ? pv.original().trim() : "";
        if (!orig.isEmpty() && Character.isDigit(orig.charAt(orig.length() - 1))) {
            return String.valueOf(pv.getBytes());
        }
        return Cell.formatBytes(pv.getBytes());
    }

    private static String fmtDelta(ParsedValue referenceValue, long absDelta) {
        String orig = referenceValue.original() != null ? referenceValue.original().trim() : "";
        if (!orig.isEmpty() && Character.isDigit(orig.charAt(orig.length() - 1))) {
            return String.valueOf(absDelta);
        }
        return Cell.formatBytes(absDelta);
    }

    private static String padLeft(String s, int width) {
        if (s.length() >= width) return s;
        return " ".repeat(width - s.length()) + s;
    }
}
