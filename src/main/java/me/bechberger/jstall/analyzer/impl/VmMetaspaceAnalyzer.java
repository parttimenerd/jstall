package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.AnalyzerOutput;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.Cell;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.analyzer.TableModel;
import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jstall.provider.requirement.DataRequirements;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyze VM.metaspace output from real {@code jcmd <pid> VM.metaspace}.
 *
 * <p>Parses the three usage rows (Non-Class, Class, Both) from the "Total Usage" block,
 * the Virtual Space block, the Waste one-liner (Both row), and the Settings block.
 * Shows per-row used-bytes trend when multiple samples are present.
 */
public class VmMetaspaceAnalyzer implements Analyzer {

    // "Total Usage - 262 loaders, 6009 classes (1383 shared):"
    private static final Pattern HEADER_PATTERN = Pattern.compile(
        "Total Usage\\s*-\\s*(\\d+)\\s+loaders,\\s+(\\d+)\\s+classes\\s+\\((\\d+)\\s+shared\\)"
    );

    // "  Non-Class: 1199 chunks,  27.38 MB capacity,  27.00 MB ( 99%) committed,  26.55 MB ( 97%) used,  457.27 KB (  2%) free,  1.00 KB ( <1%) waste"
    private static final Pattern USAGE_ROW_PATTERN = Pattern.compile(
        "^\\s*(Non-Class|Class|Both):\\s+(\\d+)\\s+chunks,\\s+" +
        "([\\d.]+)\\s+(MB|KB|GB|bytes)\\s+capacity,\\s+" +
        "([\\d.]+)\\s+(MB|KB|GB|bytes)\\s+\\([^)]*\\)\\s+committed,\\s+" +
        "([\\d.]+)\\s+(MB|KB|GB|bytes)\\s+\\([^)]*\\)\\s+used,\\s+" +
        "([\\d.]+)\\s+(MB|KB|GB|bytes)\\s+\\([^)]*\\)\\s+free,\\s+" +
        "([\\d.]+)\\s+(MB|KB|GB|bytes)\\s+\\([^)]*\\)\\s+waste"
    );

    // "  Non-class space:  64.00 MB reserved,  27.00 MB ( 42%) committed,  1 nodes."
    // "             Both:   1.06 GB reserved,  30.31 MB (  3%) committed."
    private static final Pattern VSPACE_ROW_PATTERN = Pattern.compile(
        "^\\s*(Non-class space|Class space|Both):\\s+" +
        "([\\d.]+)\\s+(MB|KB|GB|bytes)\\s+reserved,\\s+" +
        "([\\d.]+)\\s+(MB|KB|GB|bytes)\\s+\\([^)]*\\)\\s+committed"
    );

    @Override
    public String name() {
        return "vm-metaspace";
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
            .addJcmd("VM.metaspace", samples, intervalMs)
            .build();
    }

    @Override
    public AnalyzerResult analyze(ResolvedData data, Map<String, Object> options) {
        List<CollectedData> samples = data.collectedData("vm-metaspace");
        if (samples.isEmpty()) {
            return AnalyzerResult.nothing();
        }

        List<MetaspaceSnapshot> parsed = new ArrayList<>();
        for (CollectedData sample : samples) {
            MetaspaceSnapshot snapshot = parseSnapshot(sample.rawData());
            if (snapshot != null) {
                parsed.add(snapshot);
            }
        }

        if (parsed.isEmpty()) {
            return AnalyzerResult.ok("VM.metaspace not available (or no parseable summary lines)");
        }

        AnalyzerOutput structured = formatStructured(parsed);
        // Use flat text for CLI output but attach structured output for live mode
        String flatOutput = renderFlat(parsed);
        return new AnalyzerResult(flatOutput, 0, true, structured);
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    MetaspaceSnapshot parseSnapshot(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        MetaspaceHeader header = null;
        Map<String, UsageRow> usage = new LinkedHashMap<>();
        Map<String, VirtualSpaceRow> virtualSpace = new LinkedHashMap<>();
        Map<String, String> settingsMap = new LinkedHashMap<>();

        // Section flags
        boolean inVirtualSpace = false;
        boolean inChunkFreelists = false;
        boolean inWaste = false;
        boolean inInternalStats = false;
        boolean inSettings = false;

        for (String line : raw.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                continue;
            }

            // --- Detect section transitions ---
            if (trimmed.equals("Virtual space:")) {
                inVirtualSpace = true; inSettings = false; inChunkFreelists = false;
                inWaste = false; inInternalStats = false;
                continue;
            }
            if (trimmed.startsWith("Chunk freelists:")) {
                inChunkFreelists = true; inVirtualSpace = false; inSettings = false;
                inWaste = false; inInternalStats = false;
                continue;
            }
            if (trimmed.startsWith("Waste (unused")) {
                inWaste = true; inVirtualSpace = false; inSettings = false;
                inChunkFreelists = false; inInternalStats = false;
                continue;
            }
            if (trimmed.equals("Internal statistics:")) {
                inInternalStats = true; inWaste = false; inVirtualSpace = false;
                inSettings = false; inChunkFreelists = false;
                continue;
            }
            if (trimmed.equals("Settings:")) {
                inSettings = true; inInternalStats = false; inWaste = false;
                inVirtualSpace = false; inChunkFreelists = false;
                continue;
            }

            // --- Section content ---
            if (inSettings) {
                int colon = trimmed.indexOf(':');
                if (colon > 0) {
                    String key = trimmed.substring(0, colon).trim();
                    String value = trimmed.substring(colon + 1).trim();
                    if (!key.isEmpty()) {
                        settingsMap.put(key, value);
                    }
                }
                continue;
            }

            if (inVirtualSpace) {
                Matcher m = VSPACE_ROW_PATTERN.matcher(line);
                if (m.find()) {
                    long reserved = parseSize(m.group(2), m.group(3));
                    long committed = parseSize(m.group(4), m.group(5));
                    virtualSpace.put(m.group(1), new VirtualSpaceRow(reserved, committed));
                }
                continue;
            }

            if (inChunkFreelists || inWaste || inInternalStats) {
                continue;
            }

            // --- Default section (usage rows + header) ---
            if (header == null) {
                Matcher hm = HEADER_PATTERN.matcher(trimmed);
                if (hm.find()) {
                    header = new MetaspaceHeader(
                        Integer.parseInt(hm.group(1)),
                        Integer.parseInt(hm.group(2)),
                        Integer.parseInt(hm.group(3))
                    );
                    continue;
                }
            }

            Matcher um = USAGE_ROW_PATTERN.matcher(line);
            if (um.find()) {
                int chunks = Integer.parseInt(um.group(2));
                long capacity  = parseSize(um.group(3),  um.group(4));
                long committed = parseSize(um.group(5),  um.group(6));
                long used      = parseSize(um.group(7),  um.group(8));
                long free      = parseSize(um.group(9),  um.group(10));
                long waste     = parseSize(um.group(11), um.group(12));
                usage.put(um.group(1), new UsageRow(chunks, capacity, committed, used, free, waste));
            }
        }

        if (usage.isEmpty() && virtualSpace.isEmpty()) {
            return null;
        }

        MetaspaceSettings settings = settingsMap.isEmpty() ? null : new MetaspaceSettings(settingsMap);
        return new MetaspaceSnapshot(header, usage, virtualSpace, settings);
    }

    // -------------------------------------------------------------------------
    // Formatting
    // -------------------------------------------------------------------------

    private AnalyzerOutput formatStructured(List<MetaspaceSnapshot> parsed) {
        MetaspaceSnapshot first  = parsed.get(0);
        MetaspaceSnapshot latest = parsed.get(parsed.size() - 1);
        boolean hasTrend = parsed.size() > 1;

        List<String> preamble = new ArrayList<>();
        preamble.add(String.format("VM.metaspace (%d sample%s):",
            parsed.size(), parsed.size() == 1 ? "" : "s"));
        if (latest.header() != null) {
            MetaspaceHeader h = latest.header();
            preamble.add(String.format("%d loaders, %d classes (%d shared)",
                h.loaders(), h.classes(), h.sharedClasses()));
        }

        // If we have only a usage table (most common), return TableOutput directly
        if (!latest.usage().isEmpty() && latest.virtualSpace().isEmpty()) {
            TableModel usageTable = buildUsageTable(first, latest, hasTrend);
            return appendSettingsAndWaste(new AnalyzerOutput.TableOutput(preamble, usageTable), latest);
        }

        // Multiple tables → CompositeOutput
        List<AnalyzerOutput.CompositeOutput.Section> sections = new ArrayList<>();

        if (!latest.usage().isEmpty()) {
            TableModel usageTable = buildUsageTable(first, latest, hasTrend);
            sections.add(new AnalyzerOutput.CompositeOutput.Section("Usage",
                new AnalyzerOutput.TableOutput(preamble, usageTable)));
        }

        if (!latest.virtualSpace().isEmpty()) {
            TableModel vsTable = buildVirtualSpaceTable(latest);
            List<String> vsPreamble = sections.isEmpty() ? preamble : List.of();
            sections.add(new AnalyzerOutput.CompositeOutput.Section("Virtual Space",
                new AnalyzerOutput.TableOutput(vsPreamble, vsTable)));
        }

        // Waste + settings as text section
        String footer = buildFooter(latest);
        if (!footer.isEmpty()) {
            sections.add(new AnalyzerOutput.CompositeOutput.Section("Summary",
                new AnalyzerOutput.TextOutput(footer)));
        }

        if (sections.size() == 1) {
            return sections.get(0).content();
        }
        return new AnalyzerOutput.CompositeOutput(sections);
    }

    private TableModel buildUsageTable(MetaspaceSnapshot first, MetaspaceSnapshot latest, boolean hasTrend) {
        TableModel.Builder usageTable = TableModel.builder()
            .addColumn("Type",      TableModel.Alignment.LEFT)
            .addColumn("Chunks",    TableModel.Alignment.RIGHT)
            .addColumn("Capacity",  TableModel.Alignment.RIGHT)
            .addColumn("Used",      TableModel.Alignment.RIGHT)
            .addColumn("Committed", TableModel.Alignment.RIGHT)
            .addColumn("Free",      TableModel.Alignment.RIGHT);
        if (hasTrend) {
            usageTable.addColumn("Trend", TableModel.Alignment.LEFT);
        }

        for (String label : List.of("Non-Class", "Class", "Both")) {
            UsageRow latestRow = latest.usage().get(label);
            if (latestRow == null) continue;

            if (hasTrend) {
                UsageRow firstRow = first.usage().getOrDefault(label, UsageRow.ZERO);
                long delta = latestRow.usedBytes() - firstRow.usedBytes();
                String arrow = delta > 0 ? "↑" : delta < 0 ? "↓" : "→";
                usageTable.addRow(
                    Cell.text(label),
                    Cell.integer(latestRow.chunks()),
                    Cell.bytes(latestRow.capacityBytes()),
                    Cell.bytes(latestRow.usedBytes()),
                    Cell.bytes(latestRow.committedBytes()),
                    Cell.bytes(latestRow.freeBytes()),
                    Cell.text(arrow + " " + formatSignedBytes(delta) + " used"));
            } else {
                usageTable.addRow(
                    Cell.text(label),
                    Cell.integer(latestRow.chunks()),
                    Cell.bytes(latestRow.capacityBytes()),
                    Cell.bytes(latestRow.usedBytes()),
                    Cell.bytes(latestRow.committedBytes()),
                    Cell.bytes(latestRow.freeBytes()));
            }
        }
        return usageTable.build();
    }

    private TableModel buildVirtualSpaceTable(MetaspaceSnapshot latest) {
        TableModel.Builder vsTable = TableModel.builder()
            .addColumn("Space",     TableModel.Alignment.LEFT)
            .addColumn("Reserved",  TableModel.Alignment.RIGHT)
            .addColumn("Committed", TableModel.Alignment.RIGHT);

        for (String label : List.of("Non-class space", "Class space", "Both")) {
            VirtualSpaceRow row = latest.virtualSpace().get(label);
            if (row == null) continue;
            vsTable.addRow(
                Cell.text(label),
                Cell.bytes(row.reservedBytes()),
                Cell.bytes(row.committedBytes()));
        }
        return vsTable.build();
    }

    private String buildFooter(MetaspaceSnapshot latest) {
        StringBuilder sb = new StringBuilder();
        UsageRow both = latest.usage().get("Both");
        if (both != null) {
            sb.append(String.format("Waste: %s", formatBytes(both.wasteBytes())));
        }
        if (latest.settings() != null) {
            MetaspaceSettings s = latest.settings();
            if (sb.length() > 0) sb.append("\n");
            sb.append(String.format("MaxMetaspaceSize: %s", s.maxMetaspaceSize()));
            sb.append(String.format("  CompressedClassSpaceSize: %s", s.compressedClassSpaceSize()));
            sb.append(String.format("  Initial GC threshold: %s", s.initialGcThreshold()));
            sb.append(String.format("  Current GC threshold: %s", s.currentGcThreshold()));
            sb.append(String.format("  CDS: %s", s.cdsOn() ? "on" : "off"));
        }
        return sb.toString();
    }

    private AnalyzerOutput appendSettingsAndWaste(AnalyzerOutput.TableOutput base, MetaspaceSnapshot latest) {
        String footer = buildFooter(latest);
        if (footer.isEmpty()) return base;
        // Wrap in composite: table + footer text
        List<AnalyzerOutput.CompositeOutput.Section> sections = new ArrayList<>();
        sections.add(new AnalyzerOutput.CompositeOutput.Section("Usage", base));
        sections.add(new AnalyzerOutput.CompositeOutput.Section("Summary",
            new AnalyzerOutput.TextOutput(footer)));
        return new AnalyzerOutput.CompositeOutput(sections);
    }

    /** Produces flat text render (same as old format) for non-interactive CLI output. */
    private String renderFlat(List<MetaspaceSnapshot> parsed) {
        MetaspaceSnapshot latest = parsed.get(parsed.size() - 1);
        MetaspaceSnapshot first = parsed.get(0);
        boolean hasTrend = parsed.size() > 1;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("VM.metaspace (%d sample%s):\n",
            parsed.size(), parsed.size() == 1 ? "" : "s"));
        if (latest.header() != null) {
            MetaspaceHeader h = latest.header();
            sb.append(String.format("%d loaders, %d classes (%d shared)\n",
                h.loaders(), h.classes(), h.sharedClasses()));
        }
        if (!latest.usage().isEmpty()) {
            sb.append(buildUsageTable(first, latest, hasTrend).render()).append("\n");
        }
        if (!latest.virtualSpace().isEmpty()) {
            sb.append(buildVirtualSpaceTable(latest).render()).append("\n");
        }
        String footer = buildFooter(latest);
        if (!footer.isEmpty()) sb.append(footer);
        return sb.toString().stripTrailing();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Convert a floating-point size string + unit to bytes.
     * Units: {@code bytes}, {@code KB}, {@code MB}, {@code GB} (case-insensitive).
     */
    static long parseSize(String value, String unit) {
        double d = Double.parseDouble(value);
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "kb"    -> Math.round(d * 1_024);
            case "mb"    -> Math.round(d * 1_024 * 1_024);
            case "gb"    -> Math.round(d * 1_024 * 1_024 * 1_024);
            default      -> Math.round(d); // bytes
        };
    }

    /** Format bytes to two-decimal human-readable string (GB / MB / KB / bytes). */
    static String formatBytes(long bytes) {
        return Cell.formatBytes(bytes);
    }

    private static String formatSignedBytes(long bytes) {
        return (bytes >= 0 ? "+" : "-") + Cell.formatBytes(Math.abs(bytes));
    }

    private int getIntOption(Map<String, Object> options, String key, int defaultValue) {
        Object value = options.get(key);
        if (value instanceof Integer i) return i;
        if (value instanceof Number n) return n.intValue();
        return defaultValue;
    }

    private long getLongOption(Map<String, Object> options, String key, long defaultValue) {
        Object value = options.get(key);
        if (value instanceof Long l) return l;
        if (value instanceof Number n) return n.longValue();
        return defaultValue;
    }

    // -------------------------------------------------------------------------
    // Data model
    // -------------------------------------------------------------------------

    record MetaspaceHeader(int loaders, int classes, int sharedClasses) {}

    record UsageRow(int chunks, long capacityBytes, long committedBytes,
                   long usedBytes, long freeBytes, long wasteBytes) {
        static final UsageRow ZERO = new UsageRow(0, 0, 0, 0, 0, 0);
    }

    record VirtualSpaceRow(long reservedBytes, long committedBytes) {}

    record MetaspaceSettings(Map<String, String> raw) {
        String maxMetaspaceSize()        { return raw.getOrDefault("MaxMetaspaceSize", "?"); }
        String compressedClassSpaceSize(){ return raw.getOrDefault("CompressedClassSpaceSize", "?"); }
        String initialGcThreshold()      { return raw.getOrDefault("Initial GC threshold", "?"); }
        String currentGcThreshold()      { return raw.getOrDefault("Current GC threshold", "?"); }
        boolean cdsOn()                  { return "on".equalsIgnoreCase(raw.getOrDefault("CDS", "off").trim()); }
    }

    record MetaspaceSnapshot(MetaspaceHeader header,
                             Map<String, UsageRow> usage,
                             Map<String, VirtualSpaceRow> virtualSpace,
                             MetaspaceSettings settings) {}
}
