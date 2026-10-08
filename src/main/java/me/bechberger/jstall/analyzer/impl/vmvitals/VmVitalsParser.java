package me.bechberger.jstall.analyzer.impl.vmvitals;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Robust parser for VM vitals output based on the SapMachine Vitals format grammar.
 *
 * Handles all structural variants from sm11-sm27:
 * - Text format with category/header/column lines and data rows
 * - CSV format with prefixed column names
 * - Section headers: "Now:", "Last N unit:", "Samples at extremes"
 * - Memory values with dynamic units (k/m/g/t) or fixed scale
 * - Delta columns (may be empty for first sample)
 * - Invalid values (blank or "?" in raw mode)
 */
public class VmVitalsParser {

    private static final int TIMESTAMP_LEN = 19;
    private static final int TIMESTAMP_DIVIDER_LEN = 3;
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
        "(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})"
    );

    private static final Pattern LEGEND_ENTRY_PATTERN = Pattern.compile(
        "^\\s*([a-z0-9-]+):\\s*(.*)$"
    );
    private static final Pattern CONDITION_NOTE_PATTERN = Pattern.compile(
        "^\\s*\\[([a-z0-9]+)\\]:\\s*(.*)$"
    );
    private static final Pattern TAG_PATTERN = Pattern.compile(
        "\\[([a-z0-9]+)]"
    );
    private static final Pattern VITALS_VERSION_PATTERN = Pattern.compile(
        "\\(Vitals version ([0-9a-fA-F]+), pid: (\\d+)\\)"
    );

    private final String[] lines;
    private VmVitalsLegend legend;
    private List<VmVitalsSection> sections;
    private long pid;
    private String version;

    private VmVitalsParser(String rawVitals) {
        this.lines = rawVitals.split("\\r?\\n");
    }

    /**
     * Parses raw VM vitals output into a structured VmVitalsOutput.
     * Tolerates missing sections and gracefully handles malformed input.
     */
    public static VmVitalsOutput parse(String rawVitals) {
        if (rawVitals == null || rawVitals.isBlank()) {
            return null;
        }
        return new VmVitalsParser(rawVitals).doParse();
    }

    private VmVitalsOutput doParse() {
        if (isCsvReport()) {
            sections = parseCsvSections();
            legend = new VmVitalsLegend(List.of(), List.of());
            return new VmVitalsOutput(-1, "", legend, sections, legend);
        }

        extractPidAndVersion();
        legend = parseLegend();
        sections = parseSections();

        return VmVitalsOutput.of(pid, version, legend, sections);
    }

    private boolean isCsvReport() {
        return Arrays.stream(lines)
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .findFirst()
            .map(s -> s.startsWith("time,"))
            .orElse(false);
    }

    private List<VmVitalsSection> parseCsvSections() {
        List<String> nonBlank = Arrays.stream(lines)
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .toList();
        if (nonBlank.isEmpty()) {
            return List.of();
        }

        List<String> columnNames = parseCsvHeader(nonBlank.get(0));
        List<DataRow> rows = new ArrayList<>();
        for (int i = 1; i < nonBlank.size(); i++) {
            DataRow row = parseCsvRow(nonBlank.get(i), columnNames);
            if (row != null) {
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            return List.of();
        }
        return List.of(new VmVitalsSection("CSV", List.of(nonBlank.get(0)), columnNames, rows));
    }

    private void extractPidAndVersion() {
        pid = -1;
        version = "";
        
        for (String line : lines) {
            Matcher m = VITALS_VERSION_PATTERN.matcher(line);
            if (m.find()) {
                version = m.group(1);
                pid = Long.parseLong(m.group(2));
                return;
            }
        }
    }

    /**
     * Parses the legend section (lines between "Vitals:" and first data section).
     * Returns entries in order and condition notes.
     */
    private VmVitalsLegend parseLegend() {
        List<LegendEntry> entries = new ArrayList<>();
        List<LegendCondition> conditions = new ArrayList<>();

        int startIdx = findLineContaining("Vitals:");
        int endIdx = findFirstSectionHeader();
        if (endIdx < 0) {
            endIdx = lines.length;
        }

        for (int i = Math.max(0, startIdx + 1); i < endIdx; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // Skip empty lines and section headers like "-----jvm------"
            if (trimmed.isEmpty() || trimmed.startsWith("-")) {
                continue;
            }

            // Try to parse as legend entry
            Matcher entryMatcher = LEGEND_ENTRY_PATTERN.matcher(trimmed);
            if (entryMatcher.matches()) {
                String key = entryMatcher.group(1);
                String description = entryMatcher.group(2);

                Set<String> tags = extractConditionTags(description);

                entries.add(new LegendEntry(key, description, tags));
                continue;
            }

            Matcher conditionMatcher = CONDITION_NOTE_PATTERN.matcher(trimmed);
            if (conditionMatcher.matches()) {
                conditions.add(new LegendCondition(conditionMatcher.group(1), conditionMatcher.group(2)));
            }
        }

        return new VmVitalsLegend(entries, conditions);
    }

    /**
     * Extracts condition tags (like [cs], [delta], [nmt]) from a description string.
     */
    private Set<String> extractConditionTags(String description) {
        Set<String> tags = new HashSet<>();
        Matcher matcher = TAG_PATTERN.matcher(description);
        while (matcher.find()) {
            tags.add(matcher.group(1));
        }
        return tags;
    }

    private boolean isSectionHeader(String trimmedLine) {
        return trimmedLine.startsWith("Now") ||
               trimmedLine.startsWith("Last") ||
               trimmedLine.startsWith("Samples at extremes");
    }

    private int findFirstSectionHeader() {
        return IntStream.range(0, lines.length)
            .filter(i -> isSectionHeader(lines[i].trim()))
            .findFirst().orElse(-1);
    }

    private int findLineContaining(String substring) {
        return IntStream.range(0, lines.length)
            .filter(i -> lines[i].contains(substring))
            .findFirst().orElse(-1);
    }

    private List<VmVitalsSection> parseSections() {
        List<VmVitalsSection> result = new ArrayList<>();

        int i = 0;
        while (i < lines.length) {
            String trimmed = lines[i].trim();
            if (isSectionHeader(trimmed)) {
                int sectionStart = i;
                String sectionName = trimmed;

                int sectionEnd = IntStream.range(i + 1, lines.length)
                    .filter(j -> isSectionHeader(lines[j].trim()))
                    .findFirst().orElse(lines.length);

                VmVitalsSection section = parseSection(sectionName, sectionStart, sectionEnd);
                if (section != null && !section.dataRows().isEmpty()) {
                    result.add(section);
                }

                i = sectionEnd;
            } else {
                i++;
            }
        }

        return result;
    }

    /**
     * Parses a single section: header lines, column names, and data rows.
     */
    private VmVitalsSection parseSection(String sectionName, int startIdx, int endIdx) {
        List<String> headerLines = new ArrayList<>();
        List<String> columnNames = new ArrayList<>();
        List<Integer> columnStarts = List.of();
        List<DataRow> dataRows = new ArrayList<>();

        int columnHeaderIdx = -1;

        for (int i = startIdx + 1; i < endIdx; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                continue;
            }

            if (isAllDashes(trimmed)) {
                continue;
            }

            if (columnHeaderIdx < 0) {
                List<String> potentialColumns = parseColumnNames(trimmed);
                boolean hasCommonColumns = potentialColumns.stream()
                    .anyMatch(c -> c.matches("^(comm|used|num|code|cr|nd|ld|uld|csu|csc|gctr|anon|cpu-|heap-|meta-|rss-|swap-|cldg-|cls-).*$"));

                if (hasCommonColumns && potentialColumns.size() >= 2) {
                    columnHeaderIdx = i;
                    columnStarts = parseColumnStartOffsets(line);

                    for (int j = Math.max(startIdx + 1, i - 3); j < i; j++) {
                        String jTrimmed = lines[j].trim();
                        if (!jTrimmed.isEmpty() && !isAllDashes(jTrimmed)) {
                            headerLines.add(lines[j]);
                        }
                    }
                    headerLines.add(line);
                    columnNames = potentialColumns;
                    continue;
                }
            }

            if (columnHeaderIdx >= 0 && !columnNames.isEmpty() && 
                TIMESTAMP_PATTERN.matcher(trimmed).find()) {
                DataRow row = columnStarts.size() == columnNames.size()
                    ? DataRow.parseFixedWidth(line, columnNames, columnStarts, TIMESTAMP_LEN + TIMESTAMP_DIVIDER_LEN)
                    : DataRow.parse(line, columnNames);
                if (row != null) {
                    dataRows.add(row);
                }
            }
        }

        if (dataRows.isEmpty()) {
            return null;
        }

        return new VmVitalsSection(sectionName, headerLines, columnNames, dataRows);
    }

    private static boolean isAllDashes(String s) {
        return !s.isEmpty() && s.chars().allMatch(c -> c == '-');
    }

    private List<String> parseColumnNames(String trimmedHeaderLine) {
        return Arrays.stream(trimmedHeaderLine.split("\\s+"))
            .toList();
    }

    private List<Integer> parseColumnStartOffsets(String headerLine) {
        int firstColumnIndex = Math.min(TIMESTAMP_LEN + TIMESTAMP_DIVIDER_LEN, headerLine.length());
        String dataPortion = headerLine.substring(firstColumnIndex);
        List<Integer> starts = new ArrayList<>();
        boolean inToken = false;
        for (int i = 0; i < dataPortion.length(); i++) {
            char c = dataPortion.charAt(i);
            if (!Character.isWhitespace(c) && !inToken) {
                starts.add(i);
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                inToken = false;
            }
        }
        return starts;
    }

    private List<String> parseCsvHeader(String headerLine) {
        List<String> parts = splitCsvLine(headerLine);
        if (parts.isEmpty() || !"time".equals(parts.get(0))) {
            return List.of();
        }
        int end = parts.get(parts.size() - 1).isEmpty() ? parts.size() - 1 : parts.size();
        return parts.subList(1, end);
    }

    private DataRow parseCsvRow(String line, List<String> columnNames) {
        List<String> parts = splitCsvLine(line);
        if (parts.isEmpty()) {
            return null;
        }
        String timestamp = parts.get(0);
        int end = Math.min(parts.size(), columnNames.size() + 1);
        List<String> values = new ArrayList<>(parts.subList(1, end));
        while (values.size() < columnNames.size()) {
            values.add("");
        }
        return DataRow.fromTimestampAndValues(timestamp, values, columnNames);
    }

    private List<String> splitCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty() || line.endsWith(",")) {
            values.add(current.toString());
        }
        return values.stream().map(String::trim).toList();
    }
}

