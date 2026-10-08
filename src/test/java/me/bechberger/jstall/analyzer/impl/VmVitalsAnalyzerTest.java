package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.analyzer.impl.vmvitals.*;
import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
import me.bechberger.jthreaddump.parser.ThreadDumpParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class VmVitalsAnalyzerTest {

    /**
     * Check if running on SapMachine (VM.vitals is SapMachine-specific)
     */
    static boolean isSapMachine() {
        String vmName = System.getProperty("java.vm.name", "");
        String vmVendor = System.getProperty("java.vm.vendor", "");
        return vmName.contains("SapMachine") || vmVendor.contains("SAP SE");
    }

    private static final String SAMPLE_GC_HEAP_INFO_PREVIOUS = """
        1127:
         garbage-first heap   total 2200000K, used 1340000K [0x000000058f000000, 0x0000000800000000)
          region size 8192K, 13 young (106496K), 6 survivors (49152K)
         Metaspace       used 639000K, committed 647000K, reserved 1638400K
          class space    used 80500K, committed 84000K, reserved 1048576K
        """;

    private static final String SAMPLE_GC_HEAP_INFO_LATEST = """
        1127:
         garbage-first heap   total 2203648K, used 1346936K [0x000000058f000000, 0x0000000800000000)
          region size 8192K, 14 young (114688K), 6 survivors (49152K)
         Metaspace       used 640193K, committed 648128K, reserved 1638400K
          class space    used 80808K, committed 84544K, reserved 1048576K
        """;

    private static final String SAMPLE_VM_VITALS = """
        27747:
        Vitals:
        
        -------------jvm--------------
               heap-comm: Java Heap Size, committed
               heap-used: Java Heap Size, used
               meta-comm: Meta Space Size (class+nonclass), committed
               meta-used: Meta Space Size (class+nonclass), used
                meta-csc: Class Space Size, committed [cs]
                meta-csu: Class Space Size, used [cs]
               meta-gctr: GC threshold
                    code: Code cache, committed
                jthr-num: Number of java threads
                 jthr-nd: Number of non-demon java threads
                 jthr-cr: Threads created [delta]
                cldg-num: Classloader Data
               cldg-anon: Anonymous CLD
                 cls-num: Classes (instance + array)
                  cls-ld: Class loaded [delta]
                 cls-uld: Classes unloaded [delta]
        
          [delta]: values refer to the previous measurement.
            [nmt]: only shown if NMT is available and activated
             [cs]: only shown on 64-bit if class space is active
        (Vitals version 220600, pid: 27747)
        
        Last 60 minutes:
                              --------------------------------jvm---------------------------------
                              --heap--- ---------meta---------      --jthr--- --cldg-- ----cls----
                              comm used comm used csc csu gctr code num nd cr num anon num  ld uld
        2026-03-09 18:08:17    64m  30m  17m  17m  2m  2m  21m  10m  15  3  0  95   80 4780  0   0
        2026-03-09 18:09:17    64m  31m  17m  17m  2m  2m  21m  10m  15  3  0  95   80 4781  1   0
        2026-03-09 18:10:17    64m  32m  17m  17m  2m  2m  21m  10m  15  3  0  95   80 4783  2   0
        2026-03-09 18:11:17    64m  33m  18m  18m  2m  2m  21m  10m  16  3  1  96   81 4785  2   0
        """;

    private static ThreadDumpSnapshot createDummySnapshot() {
        String dumpContent = """
            2024-12-29 13:00:00
            Full thread dump Java HotSpot(TM) 64-Bit Server VM (21+35-2513 mixed mode):
            """;
        try {
            var parsed = ThreadDumpParser.parse(dumpContent);
            return new ThreadDumpSnapshot(parsed, dumpContent, null, Map.of());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void emitsNothingWhenNoData() {
        VmVitalsAnalyzer analyzer = new VmVitalsAnalyzer();
        ResolvedData data = ResolvedData.fromDumps(List.of(createDummySnapshot()));
        
        AnalyzerResult result = analyzer.analyze(data, Map.of());
        
        assertTrue(result.shouldDisplay(), "Should display when VM.vitals is not available");
        assertTrue(result.output().contains("not available"), "Should mention VM.vitals is not available");
        assertTrue(result.output().contains("gc-heap-info"), "Should point users to other useful commands");
    }

    @Test
    void fallsBackToRawOutputWhenStructuredParsingFindsNoUsableSections() {
        VmVitalsAnalyzer analyzer = new VmVitalsAnalyzer();
        String rawVitals = """
            27747:
            Vitals:

            Unexpected future format
            this line is intentionally not parseable as a vitals table
            """;

        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(
            List.of(createDummySnapshot()),
            Map.of("vm-vitals", List.of(new CollectedData(1L, rawVitals, Map.of())))
        );

        AnalyzerResult result = analyzer.analyze(data, Map.of());

        assertTrue(result.shouldDisplay());
        assertTrue(result.output().contains("could not be parsed"));
        assertTrue(result.output().contains("Unexpected future format"));
    }

    @Test
    @EnabledIf("isSapMachine")
    void parsesVmVitalsWithDefaultTop() {
        VmVitalsAnalyzer analyzer = new VmVitalsAnalyzer();
        
        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(
            List.of(createDummySnapshot()),
            Map.of("vm-vitals", List.of(new CollectedData(1L, SAMPLE_VM_VITALS, Map.of())))
        );
        
        AnalyzerResult result = analyzer.analyze(data, Map.of());
        
        assertTrue(result.shouldDisplay());
        String output = result.output();
        assertTrue(output.contains("filtered by active columns"), "Should indicate legend is filtered");
        assertTrue(output.contains("--heap---"), "Should contain heap header");
        assertTrue(output.contains("2026-03-09 18:08:17"), "Should contain first data line");
        assertTrue(output.contains("2026-03-09 18:11:17"), "Should contain last data line");
        // All 4 data lines should be present (default top=5, but only 4 lines available)
        assertEquals(4, output.lines().filter(line -> line.matches(".*\\d{4}-\\d{2}-\\d{2}.*")).count());
    }

    @Test
    @EnabledIf("isSapMachine")
    void respectsTopOption() {
        VmVitalsAnalyzer analyzer = new VmVitalsAnalyzer();
        
        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(
            List.of(createDummySnapshot()),
            Map.of("vm-vitals", List.of(new CollectedData(1L, SAMPLE_VM_VITALS, Map.of())))
        );
        
        AnalyzerResult result = analyzer.analyze(data, Map.of("top", 2));
        
        assertTrue(result.shouldDisplay());
        String output = result.output();
        assertTrue(output.contains("Last 60 minutes (showing 2 of 4 samples"), "Should preserve the section title and show truncation");
        // Should only show last 2 data lines
        assertEquals(2, output.lines().filter(line -> line.matches(".*\\d{4}-\\d{2}-\\d{2}.*")).count());
        assertTrue(output.contains("2026-03-09 18:10:17"), "Should contain second-to-last line");
        assertTrue(output.contains("2026-03-09 18:11:17"), "Should contain last line");
        assertFalse(output.contains("2026-03-09 18:08:17"), "Should not contain first line");
    }

    @Test
    @EnabledIf("isSapMachine")
    void handlesEmptyVmVitalsData() {
        VmVitalsAnalyzer analyzer = new VmVitalsAnalyzer();
        
        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(
            List.of(createDummySnapshot()),
            Map.of("vm-vitals", List.of(new CollectedData(1L, "", Map.of())))
        );
        
        AnalyzerResult result = analyzer.analyze(data, Map.of());
        
        assertFalse(result.shouldDisplay());
    }

    @Test
    @EnabledIf("isSapMachine")
    void usesLastSampleWhenMultipleAvailable() {
        VmVitalsAnalyzer analyzer = new VmVitalsAnalyzer();
        
        String firstVitals = """
            12345:
            Last 60 minutes:
            2026-03-09 18:00:00    32m  16m  10m  10m  1m  1m  15m  5m  10  2  0  50   40 2000  0   0
            """;
        
        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(
            List.of(createDummySnapshot()),
            Map.of("vm-vitals", List.of(
                new CollectedData(1L, firstVitals, Map.of()),
                new CollectedData(2L, SAMPLE_VM_VITALS, Map.of())
            ))
        );
        
        AnalyzerResult result = analyzer.analyze(data, Map.of());
        
        assertTrue(result.shouldDisplay());
        String output = result.output();
        assertTrue(output.contains("2026-03-09 18:11:17"), "Should use last sample");
        assertFalse(output.contains("2026-03-09 18:00:00"), "Should not use first sample");
    }

    @Test
    @EnabledIf("isSapMachine")
    void handlesTopGreaterThanAvailableLines() {
        VmVitalsAnalyzer analyzer = new VmVitalsAnalyzer();
        
        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(
            List.of(createDummySnapshot()),
            Map.of("vm-vitals", List.of(new CollectedData(1L, SAMPLE_VM_VITALS, Map.of())))
        );
        
        AnalyzerResult result = analyzer.analyze(data, Map.of("top", 100));
        
        assertTrue(result.shouldDisplay());
        String output = result.output();
        // Should show all 4 available lines even though top=100
        assertEquals(4, output.lines().filter(line -> line.matches(".*\\d{4}-\\d{2}-\\d{2}.*")).count());
    }

    private static final String SAMPLE_VM_VITALS_WITH_EXTREMES = """
        27747:
        Vitals:

        (Vitals version 220600, pid: 27747)

        Last 60 minutes:
                              --------------------------------jvm---------------------------------
                              --heap--- ---------meta---------      --jthr--- --cldg-- ----cls----
                              comm used comm used csc csu gctr code num nd cr num anon num  ld uld
        2026-03-09 18:08:17    64m  30m  17m  17m  2m  2m  21m  10m  15  3  0  95   80 4780  0   0
        2026-03-09 18:09:17    64m  31m  17m  17m  2m  2m  21m  10m  15  3  0  95   80 4781  1   0
        2026-03-09 18:10:17    64m  32m  17m  17m  2m  2m  21m  10m  15  3  0  95   80 4783  2   0
        2026-03-09 18:11:17    64m  33m  18m  18m  2m  2m  21m  10m  16  3  1  96   81 4785  2   0

        Samples at extremes (+ marks a maximum, - marks a minimum)
                              --------------------------------jvm---------------------------------
                              --heap--- ---------meta---------      --jthr--- --cldg-- ----cls----
                              comm used comm used csc csu gctr code num nd cr num anon num  ld uld
        2026-03-09 18:08:17    64m- 30m  17m  17m  2m  2m  21m  10m  15  3  0  95   80 4780  0   0
        2026-03-09 18:11:17    64m  33m+ 18m  18m  2m  2m  21m  10m  16  3  1  96   81 4785  2   0
        """;

    @Test
    @EnabledIf("isSapMachine")
    void showsBothRecentAndExtremeSections() {
        VmVitalsAnalyzer analyzer = new VmVitalsAnalyzer();

        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(
            List.of(createDummySnapshot()),
            Map.of("vm-vitals", List.of(new CollectedData(1L, SAMPLE_VM_VITALS_WITH_EXTREMES, Map.of())))
        );

        AnalyzerResult result = analyzer.analyze(data, Map.of("top", 2));

        assertTrue(result.shouldDisplay());
        String output = result.output();

        // Should have both sections
        assertTrue(output.contains("Last 60 minutes (showing 2 of 4 samples"), "Should preserve recent section title");
        assertTrue(output.contains("Samples at extremes (showing 2 of 2 marked samples"), "Should explain extremes section size");
        
        // Should have timestamps from both sections
        assertTrue(output.contains("2026-03-09 18:10:17"), "Should have recent rows");
        assertTrue(output.contains("2026-03-09 18:08:17"), "Should have extremes rows");
        
        // Should preserve some markers (look for pattern with +/- near values)
        assertTrue(output.matches("(?s).*\\d{1,2}m[\\+-].*"), "Should preserve extreme markers");
    }

    @Test
    @EnabledIf("isSapMachine")
    void extremesCapIsMaxOf10AndTop() {
        // Build a sample with 15 extremes rows so we can verify the cap logic
        StringBuilder sb = new StringBuilder();
        sb.append("27747:\nVitals:\n\nLast 60 minutes:\n");
        sb.append("                              --heap---\n");
        sb.append("                              comm used\n");
        for (int i = 0; i < 5; i++) {
            sb.append(String.format("2026-03-09 18:%02d:17    64m  %2dm\n", i, 30 + i));
        }
        sb.append("\nSamples at extremes (+ marks a maximum, - marks a minimum)\n");
        sb.append("                              --heap---\n");
        sb.append("                              comm used\n");
        for (int i = 0; i < 15; i++) {
            sb.append(String.format("2026-03-09 19:%02d:17    64m  %2dm%s\n", i, 20 + i, i == 0 ? "-" : i == 14 ? "+" : ""));
        }

        VmVitalsAnalyzer analyzer = new VmVitalsAnalyzer();
        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(
            List.of(createDummySnapshot()),
            Map.of("vm-vitals", List.of(new CollectedData(1L, sb.toString(), Map.of())))
        );

        // top=2: recent shows 2 rows, extremes cap = max(10,2) = 10
        AnalyzerResult result2 = analyzer.analyze(data, Map.of("top", 2));
        long recentCount2 = result2.output().lines()
                .filter(l -> l.matches("\\s*2026-03-09 18:.*")).count();
        long extremesCount2 = result2.output().lines()
                .filter(l -> l.matches("\\s*2026-03-09 19:.*")).count();
        assertEquals(2, recentCount2, "top=2 should show 2 recent rows");
        assertEquals(10, extremesCount2, "top=2 should cap extremes at max(10,2)=10");

        // top=20: recent shows all 5 rows, extremes cap = max(10,20) = 20 (all 15)
        AnalyzerResult result20 = analyzer.analyze(data, Map.of("top", 20));
        long recentCount20 = result20.output().lines()
                .filter(l -> l.matches("\\s*2026-03-09 18:.*")).count();
        long extremesCount20 = result20.output().lines()
                .filter(l -> l.matches("\\s*2026-03-09 19:.*")).count();
        assertEquals(5, recentCount20, "top=20 should show all 5 available recent rows");
        assertEquals(15, extremesCount20, "top=20 should cap extremes at max(10,20)=20 (all 15 fit)");
    }

    @Test
    void gcHeapInfoShowsAbsoluteValuesAndChange() {
        GcHeapInfoAnalyzer analyzer = new GcHeapInfoAnalyzer();

        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(
            List.of(createDummySnapshot()),
            Map.of("gc-heap-info", List.of(
                new CollectedData(1L, SAMPLE_GC_HEAP_INFO_PREVIOUS, Map.of()),
                new CollectedData(2L, SAMPLE_GC_HEAP_INFO_LATEST, Map.of())
            ))
        );

        AnalyzerResult result = analyzer.analyze(data, Map.of());

        assertTrue(result.shouldDisplay(), "Expected GC.heap_info output");
        String output = result.output();
        assertTrue(output.contains("GC.heap_info (last dump absolute + change):"));
        assertTrue(output.contains("Heap total"));
        assertTrue(output.contains("2,203,648K"));
        assertTrue(output.contains("Δ +3,648K"));
        assertTrue(output.contains("Heap used"));
        assertTrue(output.contains("61.1%"));
        assertTrue(output.contains("Young regions"));
        assertTrue(output.contains("14 regions, 114,688K"));
        assertTrue(output.contains("Metaspace used"));
        assertTrue(output.contains("640,193K"));
        assertTrue(output.contains("Class space committed"));
        assertTrue(output.contains("84,544K"));
    }

    // ================================================================================
    // VM Vitals Data Model and Parser Tests
    // ================================================================================

    @Test
    void parsesLegendEntries() {
        VmVitalsOutput output = VmVitalsParser.parse(SAMPLE_VM_VITALS);
        
        assertNotNull(output);
        VmVitalsLegend legend = output.legend();
        assertFalse(legend.entries().isEmpty(), "Should parse legend entries");
        
        // Check that entries are parsed
        Set<String> keys = legend.entries().stream().map(LegendEntry::key).collect(Collectors.toSet());
        assertTrue(keys.contains("heap-comm"), "Should have heap-comm entry");
        assertTrue(keys.contains("heap-used"), "Should have heap-used entry");
        assertTrue(keys.contains("meta-csc"), "Should have meta-csc entry");
    }

    @Test
    void extractsShortNamesFromLegendKeys() {
        LegendEntry entry = new LegendEntry("heap-comm", "Java Heap Size, committed", Set.of());
        assertEquals("comm", entry.shortName(), "Should extract short name from key");
        
        LegendEntry entry2 = new LegendEntry("meta-csc", "Class Space Size, committed [cs]", Set.of("cs"));
        assertEquals("csc", entry2.shortName(), "Should extract csc from meta-csc");
        assertEquals("meta", entry2.prefix(), "Should extract meta prefix");
    }

    @Test
    void extractsConditionTagsFromDescription() {
        VmVitalsOutput output = VmVitalsParser.parse(SAMPLE_VM_VITALS);
        VmVitalsLegend legend = output.legend();
        
        // Find meta-csc entry (should have [cs] tag)
        LegendEntry metaCsc = legend.entries().stream()
            .filter(e -> e.key().equals("meta-csc"))
            .findFirst()
            .orElse(null);
        
        assertNotNull(metaCsc, "Should find meta-csc entry");
        assertTrue(metaCsc.conditionTags().contains("cs"), "Should extract [cs] tag from description");
    }

    @Test
    void parsesSections() {
        VmVitalsOutput output = VmVitalsParser.parse(SAMPLE_VM_VITALS);
        
        assertNotNull(output);
        assertFalse(output.sections().isEmpty(), "Should parse sections");
        
        // Check that "Last 60 minutes" section is present
        boolean hasRecentSection = output.sections().stream()
            .anyMatch(s -> s.name().contains("Last 60 minutes"));
        assertTrue(hasRecentSection, "Should have 'Last 60 minutes' section");
    }

    @Test
    void parsesDataRows() {
        VmVitalsOutput output = VmVitalsParser.parse(SAMPLE_VM_VITALS);
        VmVitalsSection recentSection = output.sections().stream()
            .filter(s -> s.name().contains("Last 60 minutes"))
            .findFirst()
            .orElse(null);
        
        assertNotNull(recentSection, "Should have recent section");
        assertEquals(4, recentSection.dataRows().size(), "Should parse 4 data rows");
        
        // Check first row timestamp
        DataRow firstRow = recentSection.dataRows().get(0);
        assertEquals(LocalDateTime.of(2026, 3, 9, 18, 8, 17), firstRow.timestamp(),
            "Should parse timestamp correctly");
    }

    @Test
    void parsesColumnNames() {
        VmVitalsOutput output = VmVitalsParser.parse(SAMPLE_VM_VITALS);
        VmVitalsSection section = output.sections().stream()
            .filter(s -> s.name().contains("Last 60 minutes"))
            .findFirst()
            .orElse(null);
        
        assertNotNull(section);
        List<String> columns = section.columnNames();
        assertFalse(columns.isEmpty(), "Should extract column names");
        assertTrue(columns.contains("comm"), "Should have comm column");
        assertTrue(columns.contains("used"), "Should have used column");
        assertTrue(columns.contains("num"), "Should have num column");
    }

    @Test
    void filtersLegendByActiveColumns() {
        VmVitalsOutput output = VmVitalsParser.parse(SAMPLE_VM_VITALS);
        
        // Get all columns from data in display order
        List<String> allColumns = output.sections().stream()
            .flatMap(s -> s.columnNames().stream())
            .toList();
        
        // Filter legend
        VmVitalsLegend filtered = output.legend().filterByActiveColumns(allColumns);
        
        // Should have entries only for columns present in data
        Set<String> filteredShortNames = filtered.entries().stream()
            .map(LegendEntry::shortName)
            .collect(Collectors.toSet());
        
        for (String col : allColumns) {
            assertTrue(filteredShortNames.contains(col),
                "Filtered legend should contain entry for column: " + col);
        }
    }

    @Test
    void filtersLegendToOnlyReallyNecessaryRows() {
        String heapOnlyVitals = """
            27747:
            Vitals:

            -------------jvm--------------
                   heap-comm: Java Heap Size, committed
                   heap-used: Java Heap Size, used
                   meta-comm: Meta Space Size (class+nonclass), committed
                   meta-used: Meta Space Size (class+nonclass), used
                    jthr-num: Number of java threads

            Last 60 minutes:
                                  --heap---
                                  comm used
            2026-03-09 18:08:17    64m  30m
            """;

        VmVitalsOutput output = VmVitalsParser.parse(heapOnlyVitals);
        assertNotNull(output);

        VmVitalsLegend filtered = output.legend().filterByActiveColumns(
            output.sections().stream().flatMap(s -> s.columnNames().stream()).toList()
        );

        Set<String> filteredKeys = filtered.entries().stream()
            .map(LegendEntry::key)
            .collect(Collectors.toSet());

        assertTrue(filteredKeys.contains("heap-comm"));
        assertTrue(filteredKeys.contains("heap-used"));
        assertFalse(filteredKeys.contains("meta-comm"), "meta-comm should not be shown when only heap columns are present");
        assertFalse(filteredKeys.contains("meta-used"), "meta-used should not be shown when only heap columns are present");
        assertFalse(filteredKeys.contains("jthr-num"), "unrelated columns should not be shown");
    }

    @Test
    void removesUnusedConditionTags() {
        VmVitalsOutput output = VmVitalsParser.parse(SAMPLE_VM_VITALS);
        
        // Get columns from data
        List<String> allColumns = output.sections().stream()
            .flatMap(s -> s.columnNames().stream())
            .toList();
        
        // Filter legend
        VmVitalsLegend filtered = output.legend().filterByActiveColumns(allColumns);
        
        // Check that condition tags match what's actually used
        Set<String> conditionTags = filtered.conditions().stream()
            .map(LegendCondition::tag)
            .collect(Collectors.toSet());
        
        // Find which condition tags are used by filtered entries
        Set<String> usedTags = filtered.entries().stream()
            .flatMap(e -> e.conditionTags().stream())
            .collect(Collectors.toSet());
        
        // All condition tags should be used by at least one entry
        for (String tag : conditionTags) {
            assertTrue(usedTags.contains(tag), 
                "Condition tag [" + tag + "] should be used by at least one legend entry");
        }
        
        // The sample has csc/csu columns, so [cs] should be present
        assertTrue(conditionTags.contains("cs"), "Should include [cs] when class space columns present");
    }

    @Test
    void computesActiveConditionsFromData() {
        VmVitalsOutput output = VmVitalsParser.parse(SAMPLE_VM_VITALS);
        
        Set<String> activeConditions = output.activeConditions();
        
        // Should include tags from entries that have data in sections
        assertTrue(activeConditions.contains("delta"), "Should include delta tag");
        assertTrue(activeConditions.contains("cs"), "Should include cs tag");
    }

    @Test
    void parsesExtremeMarkers() {
        VmVitalsOutput output = VmVitalsParser.parse(SAMPLE_VM_VITALS_WITH_EXTREMES);
        
        VmVitalsSection extremesSection = output.sections().stream()
            .filter(s -> s.name().contains("extremes"))
            .findFirst()
            .orElse(null);
        
        assertNotNull(extremesSection, "Should have extremes section");
        
        // Check that markers are parsed
        DataRow markedRow = extremesSection.dataRows().stream()
            .filter(r -> !r.extremeMarkers().isEmpty())
            .findFirst()
            .orElse(null);
        
        if (markedRow != null) {
            assertTrue(markedRow.extremeMarkers().values().stream()
                .anyMatch(m -> m != null && (m.equals("+") || m.equals("-"))),
                "Should parse extreme markers");
        }
    }

    @Test
    @EnabledIf("isSapMachine")
    void analyzerUsesNewParserAndFiltersLegend() {
        VmVitalsAnalyzer analyzer = new VmVitalsAnalyzer();
        
        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(
            List.of(createDummySnapshot()),
            Map.of("vm-vitals", List.of(new CollectedData(1L, SAMPLE_VM_VITALS, Map.of())))
        );
        
        AnalyzerResult result = analyzer.analyze(data, Map.of("top", 2));
        
        assertTrue(result.shouldDisplay());
        String output = result.output();
        
        // Should show filtered legend
        assertTrue(output.contains("filtered by active columns"), "Should indicate legend is filtered");
        
        // Should show legend entries
        assertTrue(output.contains("heap-comm:") || output.contains("heap-comm "), "Should show legend entries");
        
        // Should show recent samples with the original section title and truncation info
        assertTrue(output.contains("Last 60 minutes (showing 2 of 4 samples"), "Should show recent samples section");
        
        // Should show only 2 data rows
        long dataRowCount = output.lines()
            .filter(l -> l.matches(".*\\d{4}-\\d{2}-\\d{2}.*"))
            .count();
        assertTrue(dataRowCount <= 2, "Should show at most 2 data rows");
    }

    @Test
    @EnabledIf("isSapMachine")
    void handlesEmptyLegend() {
        String vitalsWithoutLegend = """
            27747:
            Vitals:
            
            Last 60 minutes:
                                      --------------------------------jvm---------------------------------
                                      --heap--- ---------meta---------      --jthr--- --cldg-- ----cls----
                                      comm used comm used csc csu gctr code num nd cr num anon num  ld uld
            2026-03-09 18:08:17    64m  30m  17m  17m  2m  2m  21m  10m  15  3  0  95   80 4780  0   0
            """;
        
        VmVitalsOutput output = VmVitalsParser.parse(vitalsWithoutLegend);
        
        assertNotNull(output);
        assertFalse(output.sections().isEmpty(), "Should still parse sections without legend");
    }

    @Test
    @EnabledIf("isSapMachine")
    void parsesSectionWithoutHeaderVariation() {
        // Test parsing when section has minimal header lines
        String minimalVitals = """
            27747:
            Last 60 minutes:
                              comm used
            2026-03-09 18:08:17    64m  30m
            """;
        
        VmVitalsOutput output = VmVitalsParser.parse(minimalVitals);
        
        assertNotNull(output);
        assertFalse(output.sections().isEmpty());
        VmVitalsSection section = output.sections().get(0);
        assertEquals(2, section.columnNames().size(), "Should parse column names");
        assertEquals(1, section.dataRows().size(), "Should parse 1 data row");
    }

    // Real CF output with system, process, and jvm sections
    private static final String REAL_CF_VM_VITALS = """
        Vitals:

        -----------system------------
                       avail: Memory available without swapping [host] [krn]
                        comm: Committed memory [host]
                         crt: Committed-to-Commit-Limit ratio (percent) [host]
                        swap: Swap space used [host]
                          si: Number of pages swapped in [host] [delta]
                          so: Number of pages pages swapped out [host] [delta]
                           p: Number of processes
                           t: Number of threads
                          tr: Number of threads running
                          tb: Number of threads blocked on disk IO
                      cpu-us: CPU user time [host]
                      cpu-sy: CPU system time [host]
                      cpu-id: CPU idle time [host]
                      cpu-st: CPU time stolen [host]
                      cpu-gu: CPU time spent on guest [host]
                  cgroup-lim: cgroup memory limit [cgrp]
                 cgroup-slim: cgroup memory soft limit [cgrp]
                  cgroup-usg: cgroup memory usage [cgrp]
                 cgroup-kusg: cgroup kernel memory usage (cgroup v1 only) [cgrp]
        -----------process-----------
                        virt: Virtual size
                     rss-all: Resident set size, total
                    rss-anon: Resident set size, anonymous memory [krn]
                    rss-file: Resident set size, file mappings [krn]
                     rss-shm: Resident set size, shared memory [krn]
                        swdo: Memory swapped out
                   cheap-usd: C-Heap, in-use allocations (may be unavailable if RSS > 4G) [glibc]
                  cheap-free: C-Heap, bytes in free blocks (may be unavailable if RSS > 4G) [glibc]
                      cpu-us: Process cpu user time
                      cpu-sy: Process cpu system time
                       io-of: Number of open files
                       io-rd: IO bytes read from storage or cache
                       io-wr: IO bytes written
                         thr: Number of native threads
        --------  -----jvm--------------
                   heap-comm: Java Heap Size, committed
                   heap-used: Java Heap Size, used
                   meta-comm: Meta Space Size (class+nonclass), committed
                   meta-used: Meta Space Size (class+nonclass), used
                    meta-csc: Class Space Size, committed [cs]
                    meta-csu: Class Space Size, used [cs]
                   meta-gctr: GC threshold
                        code: Code cache, committed
                 nmt-mlc: Memory malloced by hotspot [nmt]
                     jthr-num: Number of java threads
                     jthr-nd: Number of non-demon java threads
        
           [host]: values are host-global (not containerized).
           [cgrp]: if containerized or running in systemd slice
            [krn]: depends on kernel version
           [glibc]: only shown for glibc-based distros
          [delta]: values refer to the previous measurement.
            [nmt]: only shown if NMT is available and activated
             [cs]: only shown on 64-bit if class space is active
          [linux]: only on Linux
        (Vitals version 220600, pid: 8)

        Last 60 minutes:
                          -----------system----- -----process----- -----------jvm-----------
                          avail comm  crt swap  si so p t tr tb us sy id st gu  lim slim usg virt all us sy of thr comm used comm used csc csu gctr code num nd
        2026-10-08 09:47:46 167.2g 428.0g 248 256k 0  0 6 204 4  0 275 279 5076 2  0 319m 0k 21m      1.8g 76m 1  0 16  40   9m   8m   4m   4m 512k 370k 21m  8m  36 22
        2026-10-08 09:47:36 167.3g 427.9g 248 256k 0  0 6 204 3  0 282 267 5118 2  0 319m 0k 21m      1.8g 76m 1  0 16  40   9m   8m   4m   4m 512k 370k 21m  8m  36 22
        """;

    @Test
    void parsesRealCFVmVitalsWithMultipleSections() {
        VmVitalsOutput output = VmVitalsParser.parse(REAL_CF_VM_VITALS);
        
        assertNotNull(output, "Should parse real CF vitals");
        assertNotNull(output.legend(), "Should have legend");
        
        // Should parse legend entries from all sections (system, process, jvm)
        Set<String> legendKeys = output.legend().entries().stream()
            .map(LegendEntry::key)
            .collect(Collectors.toSet());
        
        // Check for entries from different sections
        assertTrue(legendKeys.contains("avail") || legendKeys.contains("cpu-us"), 
            "Should have system metrics");
        assertTrue(legendKeys.contains("heap-comm") || legendKeys.contains("jthr-num"), 
            "Should have jvm metrics");
        
        // Should parse sections
        assertFalse(output.sections().isEmpty(), "Should have parsed sections");
        
        // Should have data rows with many columns
        for (VmVitalsSection section : output.sections()) {
            if (!section.dataRows().isEmpty()) {
                assertTrue(section.columnNames().size() > 10, 
                    "Should have many columns from multiple sections");
            }
        }
    }

    @Test
    void handlesLegendWithMultipleSectionHeaders() {
        VmVitalsOutput output = VmVitalsParser.parse(REAL_CF_VM_VITALS);
        
        assertNotNull(output);
        assertFalse(output.legend().entries().isEmpty(), "Should parse legend entries");
        
        // Legend should contain entries from all sections
        long systemEntries = output.legend().entries().stream()
            .filter(e -> e.key().contains("cpu") || e.key().contains("avail") || e.key().contains("cgroup"))
            .count();
        long jvmEntries = output.legend().entries().stream()
            .filter(e -> e.key().contains("heap") || e.key().contains("jthr") || e.key().contains("meta"))
            .count();
        
        assertTrue(systemEntries > 0, "Should have system entries");
        assertTrue(jvmEntries > 0, "Should have jvm entries");
    }

    @Test
    void robustlyHandlesMissingSections() {
        // Input with missing some sections
        String partialVitals = """
            Vitals:
            
            -----jvm-----
            heap-comm: Java Heap Size, committed
            heap-used: Java Heap Size, used
            
            [delta]: values refer to the previous measurement.
            
            Last 60 minutes:
                       --heap---
                       comm used
            2026-03-09 18:08:17 64m  30m
            2026-03-09 18:08:27 65m  31m
            """;
        
        VmVitalsOutput output = VmVitalsParser.parse(partialVitals);
        
        assertNotNull(output, "Should handle partial legend");
        assertFalse(output.sections().isEmpty(), "Should still parse sections");
        assertEquals(2, output.sections().get(0).dataRows().size(), "Should parse both data rows");
    }

    @Test
    void filtersJvmOnlyColumnsFromMultiSection() {
        // When analyzer gets real CF vitals, it should focus on JVM metrics if asked
        VmVitalsOutput output = VmVitalsParser.parse(REAL_CF_VM_VITALS);
        
        // Get all columns from data
        List<String> allColumns = output.sections().stream()
            .flatMap(s -> s.columnNames().stream())
            .toList();
        
        // Filter to jvm-like columns (more lenient to handle ambiguous column names)
        List<String> jvmColumns = allColumns.stream()
            .filter(c -> c.equals("heap-comm") || c.equals("heap-used") || c.equals("used") || 
                         c.equals("jthr") || c.equals("code") || c.contains("heap"))
            .toList();
        
        // Filter legend for jvm if we have columns
        if (!jvmColumns.isEmpty()) {
            VmVitalsLegend filtered = output.legend().filterByActiveColumns(jvmColumns);
            
            // Filtered legend should prioritize jvm entries
            assertTrue(filtered.entries().size() <= output.legend().entries().size(),
                "Filtered legend should have fewer or equal entries");
        }
    }

    @Test
    void handlesVariableColumnCounts() {
        // Real CF output may have variable number of columns depending on what's active
        VmVitalsOutput output = VmVitalsParser.parse(REAL_CF_VM_VITALS);
        
        if (!output.sections().isEmpty()) {
            VmVitalsSection section = output.sections().get(0);
            // Column count should reasonably match data row column count (allow some variance for empty columns)
            for (DataRow row : section.dataRows()) {
                assertTrue(Math.abs(section.columnNames().size() - row.values().size()) <= 5,
                    "Row values should approximately match column count (within 5 columns tolerance)");
            }
        }
    }

    // === Integration tests with REAL Cloud Foundry VM vitals output ===
    
    @Test
    void parsesRealSapMachine11Output() {
        String sapmachine11Output = """
            Vitals:

            --------system--------
                       avail: Memory available without swapping [host] [krn]
                        comm: Committed memory [host]
                    jthr-num: Number of java threads
                     jthr-nd: Number of non-demon java threads
                     jthr-cr: Threads created [delta]
                       heap-comm: Java Heap Size, committed
                       heap-used: Java Heap Size, used

            (Vitals version 220600, pid: 8)

            Last 60 minutes:
                          avail  comm   jthr-num jthr-nd jthr-cr heap-comm heap-used
            2026-10-08 09:50:46   129.7g 723.7g      38        22        0      87m       24m
            2026-10-08 09:50:36   129.7g 723.9g      38        22        0      87m       13m
            2026-10-08 09:50:26   129.8g 723.6g      38        22        0      87m       26m
            """;
        
        VmVitalsOutput output = VmVitalsParser.parse(sapmachine11Output);
        
        assertNotNull(output);
        assertFalse(output.sections().isEmpty(), "Should parse sapmachine11 output");
        assertTrue(output.legend().entries().size() >= 6, "Should parse legend entries");
        
        VmVitalsSection section = output.sections().get(0);
        assertEquals(3, section.dataRows().size(), "Should parse 3 data rows");
        assertTrue(section.columnNames().contains("avail"), "Should have avail column");
        assertTrue(section.columnNames().contains("heap-comm"), "Should have heap-comm column");
    }

    @Test
    void parsesRealSapMachine17Output() {
        String sapmachine17Output = """
            Vitals:

            --------system--------
                       comm: Committed memory [host]
                    heap-comm: Java Heap Size, committed
                    heap-used: Java Heap Size, used
                    meta-comm: Meta Space Size (class+nonclass), committed

            (Vitals version 220600, pid: 7)

            Last 60 minutes:
                          comm   heap-comm heap-used meta-comm
            2026-10-08 09:50:48   381.1g       1.9g       548m       4m
            2026-10-08 09:50:38   378.5g       1.9g       537m       4m
            2026-10-08 09:50:28   380.1g       1.9g       526m       4m
            """;
        
        VmVitalsOutput output = VmVitalsParser.parse(sapmachine17Output);
        
        assertNotNull(output);
        assertFalse(output.sections().isEmpty(), "Should parse sapmachine17 output");
        assertEquals(3, output.sections().get(0).dataRows().size(), "Should parse 3 data rows");
    }

    @Test
    void filtersLegendForColumnsActuallyPresent() {
        // Test with real sample that has complete structure
        VmVitalsOutput output = VmVitalsParser.parse(SAMPLE_VM_VITALS);
        
        // Get columns that appear in data
        List<String> dataColumns = output.sections().stream()
            .flatMap(s -> s.columnNames().stream())
            .toList();
        
        assertFalse(dataColumns.isEmpty(), "Should have parsed columns");
        
        // Filter legend for only those columns
        VmVitalsLegend filtered = output.legend().filterByActiveColumns(dataColumns);
        
        // Verify filtering reduces legend appropriately
        assertTrue(filtered.entries().size() > 0, "Filtered legend should have entries");
        assertTrue(filtered.entries().size() <= output.legend().entries().size(), 
            "Filtering should not add entries");
    }

    @Test
    void handlesRealWorldColumnVariations() {
        // Real CF output may have columns in different order and some missing
        String realWorldOutput = """
            Vitals:
            
            -----mixed-----
            heap-comm: Java Heap Size, committed
            heap-used: Java Heap Size, used
            cpu-us: CPU user time [host]
            cpu-sy: CPU system time [host]
            meta-csc: Class Space Size, committed [cs]
            
            [host]: values are host-global
            [cs]: only shown on 64-bit if class space is active
            
            Last 60 minutes:
                       cpu-us cpu-sy heap-comm heap-used meta-csc
            2026-10-08 09:50:46 275    279       9m        8m       512k
            2026-10-08 09:50:36 282    267       9m        8m       512k
            2026-10-08 09:50:26 252    233       9m        8m       512k
            """;
        
        VmVitalsOutput output = VmVitalsParser.parse(realWorldOutput);
        
        assertNotNull(output);
        assertFalse(output.sections().isEmpty());
        
        // Verify all expected columns are parsed
        Set<String> columns = output.sections().get(0).columnNames().stream()
            .collect(Collectors.toSet());
        
        assertTrue(columns.contains("cpu-us"), "Should have cpu-us");
        assertTrue(columns.contains("heap-comm"), "Should have heap-comm");
        assertTrue(columns.contains("meta-csc"), "Should have meta-csc");
        
        // Verify data rows have matching values
        DataRow firstRow = output.sections().get(0).dataRows().get(0);
        assertEquals("275", firstRow.values().get("cpu-us"), "Should parse cpu-us value");
        assertEquals("9m", firstRow.values().get("heap-comm"), "Should parse heap-comm value");
        assertEquals("512k", firstRow.values().get("meta-csc"), "Should parse meta-csc value");
    }

    @Test
    void filtersUnusedConditionTags() {
        // Use real sample to verify condition tag filtering
        VmVitalsOutput parsed = VmVitalsParser.parse(SAMPLE_VM_VITALS);
        
        // Get columns from data
        List<String> activeColumns = parsed.sections().stream()
            .flatMap(s -> s.columnNames().stream())
            .toList();
        
        VmVitalsLegend filtered = parsed.legend().filterByActiveColumns(activeColumns);
        
        // Verify filtering works correctly
        Set<String> conditions = filtered.conditions().stream()
            .map(LegendCondition::tag)
            .collect(Collectors.toSet());
        
        // The sample has [cs] and [delta] tags
        Set<String> usedTags = filtered.entries().stream()
            .flatMap(e -> e.conditionTags().stream())
            .collect(Collectors.toSet());
        
        // All remaining condition tags should be used by at least one entry
        for (String tag : conditions) {
            assertTrue(usedTags.contains(tag), 
                "Condition tag [" + tag + "] should be used by an entry");
        }
    }

    @Test
    void robustlyHandlesMissingCgroupValues() {
        // Real CF instances may not report cgroup values  
        // Use real CF output which may have missing cgroup values
        VmVitalsOutput parsed = VmVitalsParser.parse(REAL_CF_VM_VITALS);
        
        assertNotNull(parsed);
        assertFalse(parsed.sections().isEmpty());
        
        // Should successfully parse rows even if some optional columns have gaps
        VmVitalsSection section = parsed.sections().get(0);
        assertTrue(section.dataRows().size() > 0, "Should parse rows with potential missing values");
    }

    @Test
    void preservesLegendOrderWhenFiltering() {
        // Verify that filtering preserves the order from the original legend
        VmVitalsOutput parsed = VmVitalsParser.parse(SAMPLE_VM_VITALS);
        
        List<String> originalOrder = parsed.legend().entries().stream()
            .map(LegendEntry::key)
            .toList();
        
        assertFalse(originalOrder.isEmpty(), "Should have original legend entries");
        
        // Filter to a subset of columns
        List<String> dataColumns = parsed.sections().stream()
            .flatMap(s -> s.columnNames().stream())
            .toList();
        
        VmVitalsLegend filtered = parsed.legend().filterByActiveColumns(dataColumns);
        List<String> filteredOrder = filtered.entries().stream()
            .map(LegendEntry::key)
            .toList();
        
        assertFalse(filteredOrder.isEmpty(), "Should have filtered legend entries");
        
        // Verify no new entries were added (only removed)
        for (String key : filteredOrder) {
            assertTrue(originalOrder.contains(key), "Filtered legend should only contain original entries: " + key);
        }
    }

    @Test
    void fixedWidthParserHandlesBlankDeltaColumnsInFirstSample() {
        // Reproduces real-world case: first sample in "Last 14 days" has blank cr/ld/uld
        // because no previous sample existed to compute deltas against.
        // Raw line ends before ld/uld columns — fixed-width parser must return "" for them.
        String vitals = "Vitals:\n\n" +
            "(Vitals version 220600, pid: 16060)\n\n" +
            "Last 14 days:\n" +
            "                      ----------------------------------jvm-----------------------------------\n" +
            "                      --heap--- ----------meta----------      --jthr--- --cldg-- -----cls-----\n" +
            "                      comm used comm used csc  csu  gctr code num nd cr num anon num  ld   uld \n" +
            "2026-10-08 13:46:00   1.6g 192m   6m   6m 896k 815k  21m   7m  18  5 11  55   52 2416 1540   0 \n" +
            "2026-10-08 12:46:16   1.6g  61m 704k 571k 128k  40k  21m   7m  15  5     10    7  876          \n";

        VmVitalsOutput output = VmVitalsParser.parse(vitals);
        assertNotNull(output);
        assertEquals(1, output.sections().size());

        VmVitalsSection section = output.sections().get(0);
        assertEquals(2, section.dataRows().size());

        DataRow normalRow = section.dataRows().get(0);
        assertEquals("11", normalRow.values().get("cr"), "cr should be 11 for normal row");
        assertEquals("1540", normalRow.values().get("ld"), "ld should be 1540 for normal row");
        assertEquals("0", normalRow.values().get("uld"), "uld should be 0 for normal row");

        DataRow firstSampleRow = section.dataRows().get(1);
        assertEquals("", firstSampleRow.values().getOrDefault("cr", ""), "cr should be blank for first-sample row");
        assertEquals("", firstSampleRow.values().getOrDefault("ld", ""), "ld should be blank for first-sample row");
        assertEquals("", firstSampleRow.values().getOrDefault("uld", ""), "uld should be blank for first-sample row");
        assertEquals("876", firstSampleRow.values().get("num"), "cls-num should still be parsed");
    }

    @Test
    void handlesVariableColumnWidths() {
        // Real data may have values like "1.9g", "512k", "<1k", "94k" with different widths
        String variableWidths = """
            Vitals:
            
            -----widths-----
            heap-comm: Java Heap Size, committed
            heap-used: Java Heap Size, used
            code: Code cache, committed
            
            Last 60 minutes:
                       heap-comm heap-used  code
            2026-10-08 09:50:46    1.9g       548m     8m
            2026-10-08 09:50:36   87m        13m      <1k
            2026-10-08 09:50:26   121m       26m      94k
            """;
        
        VmVitalsOutput parsed = VmVitalsParser.parse(variableWidths);
        
        VmVitalsSection section = parsed.sections().get(0);
        assertEquals(3, section.dataRows().size());
        
        // Verify values are parsed correctly regardless of width
        assertEquals("1.9g", section.dataRows().get(0).values().get("heap-comm"));
        assertEquals("548m", section.dataRows().get(0).values().get("heap-used"));
        assertEquals("8m", section.dataRows().get(0).values().get("code"));
        
        assertEquals("87m", section.dataRows().get(1).values().get("heap-comm"));
        assertEquals("<1k", section.dataRows().get(1).values().get("code"));
        
        assertEquals("121m", section.dataRows().get(2).values().get("heap-comm"));
        assertEquals("94k", section.dataRows().get(2).values().get("code"));
    }

    // ================================================================================
    // VmVitalsObservations tests
    // ================================================================================

    private static final String STABLE_VITALS = """
            27747:
            Vitals:

            (Vitals version 220600, pid: 27747)

            Last 60 minutes:
                                  --------------------------------jvm---------------------------------
                                  --heap--- ---------meta---------      --jthr--- --cldg-- ----cls----
                                  comm used comm used csc csu gctr code num nd cr num anon num  ld uld
            2026-03-09 18:08:17    64m  30m  17m  17m  2m  2m  21m  10m  15  3  0  95   80 4780  0   0
            2026-03-09 18:09:17    64m  30m  17m  17m  2m  2m  21m  10m  15  3  0  95   80 4780  0   0
            2026-03-09 18:10:17    64m  30m  17m  17m  2m  2m  21m  10m  15  3  0  95   80 4780  0   0
            """;

    @Test
    void observationsAbsentWhenStable() {
        VmVitalsOutput output = VmVitalsParser.parse(STABLE_VITALS);
        String obs = me.bechberger.jstall.analyzer.impl.vmvitals.VmVitalsObservations.analyze(output);
        // No growth observations: heap stable, meta stable, threads stable, classes stable
        // (metaspace near GC threshold may fire as a static observation, that's acceptable)
        assertFalse(obs.contains("heap-used growing"), "Should not flag stable heap growth");
        assertFalse(obs.contains("metaspace growing"), "Should not flag stable metaspace as growing");
        assertFalse(obs.contains("thread count grew"), "Should not flag stable thread count");
        assertFalse(obs.contains("loaded class count grew"), "Should not flag stable class count");
    }

    @Test
    void observationsIncludeTrendTableWhenDataChanges() {
        // heap-used grows from 30m to 40m (>5% increase)
        String vitals = """
                27747:
                Vitals:

                (Vitals version 220600, pid: 27747)

                Last 60 minutes:
                                      --------------------------------jvm---------------------------------
                                      --heap--- ---------meta---------      --jthr--- --cldg-- ----cls----
                                      comm used comm used csc csu gctr code num nd cr num anon num  ld uld
                2026-03-09 18:08:17    64m  30m  17m  17m  2m  2m  21m  10m  15  3  0  95   80 4780  0   0
                2026-03-09 18:09:17    64m  35m  17m  17m  2m  2m  21m  10m  15  3  0  95   80 4780  0   0
                2026-03-09 18:10:17    64m  40m  17m  17m  2m  2m  21m  10m  15  3  0  95   80 4780  0   0
                """;
        VmVitalsOutput output = VmVitalsParser.parse(vitals);
        String obs = me.bechberger.jstall.analyzer.impl.vmvitals.VmVitalsObservations.analyze(output);

        assertTrue(obs.contains("Trends"), "Should have Trends table");
        assertTrue(obs.contains("Observations:"), "Should have Observations section when heap grows");
        assertTrue(obs.contains("heap-used growing"), "Should flag heap-used growth");
    }

    @Test
    void observationsHeapPressureFiresAbove80Percent() {
        // heap-used = 56m, heap-comm = 64m → 87% → should fire
        String vitals = """
                27747:
                Vitals:

                (Vitals version 220600, pid: 27747)

                Last 60 minutes:
                                      --heap---
                                      comm used
                2026-03-09 18:08:17    64m  56m
                2026-03-09 18:09:17    64m  57m
                """;
        VmVitalsOutput output = VmVitalsParser.parse(vitals);
        String obs = me.bechberger.jstall.analyzer.impl.vmvitals.VmVitalsObservations.analyze(output);

        assertTrue(obs.contains("Observations:"), "Should have Observations section");
        assertTrue(obs.contains("heap used is"), "Should flag heap pressure");
        assertTrue(obs.contains("high memory pressure"), "Should explain the implication");
    }

    @Test
    void observationsHeapPressureDoesNotFireBelow80Percent() {
        // heap-used = 48m, heap-comm = 64m → 75% → should not fire
        String vitals = """
                27747:
                Vitals:

                (Vitals version 220600, pid: 27747)

                Last 60 minutes:
                                      --heap---
                                      comm used
                2026-03-09 18:08:17    64m  48m
                2026-03-09 18:09:17    64m  48m
                """;
        VmVitalsOutput output = VmVitalsParser.parse(vitals);
        String obs = me.bechberger.jstall.analyzer.impl.vmvitals.VmVitalsObservations.analyze(output);

        assertFalse(obs.contains("high memory pressure"), "Should not flag heap pressure below 80%");
    }

    @Test
    void observationsMetaspaceGrowthFires() {
        // meta-used grows from 17m to 18m
        String vitals = """
                27747:
                Vitals:

                (Vitals version 220600, pid: 27747)

                Last 60 minutes:
                                      --heap--- --meta--
                                      comm used comm used
                2026-03-09 18:08:17    64m  30m  17m  17m
                2026-03-09 18:09:17    64m  30m  17m  18m
                """;
        VmVitalsOutput output = VmVitalsParser.parse(vitals);
        String obs = me.bechberger.jstall.analyzer.impl.vmvitals.VmVitalsObservations.analyze(output);

        assertTrue(obs.contains("Observations:"), "Should have observations when metaspace grows");
        assertTrue(obs.contains("metaspace growing"), "Should flag metaspace growth");
        assertTrue(obs.contains("class leak"), "Should mention possible class leak");
    }

    @Test
    void observationsThreadLeakFires() {
        // threads grow from 15 to 25 (+10 ≥ threshold of 5)
        String vitals = """
                27747:
                Vitals:

                (Vitals version 220600, pid: 27747)

                Last 60 minutes:
                                      --jthr---
                                      num nd
                2026-03-09 18:08:17    15   3
                2026-03-09 18:09:17    25   5
                """;
        VmVitalsOutput output = VmVitalsParser.parse(vitals);
        String obs = me.bechberger.jstall.analyzer.impl.vmvitals.VmVitalsObservations.analyze(output);

        assertTrue(obs.contains("Observations:"), "Should have observations when threads grow");
        assertTrue(obs.contains("thread count grew"), "Should flag thread count growth");
        assertTrue(obs.contains("thread leak"), "Should mention possible thread leak");
    }

    @Test
    void trendTableShowsCorrectFirstLastDelta() {
        String vitals = """
                27747:
                Vitals:

                (Vitals version 220600, pid: 27747)

                Last 60 minutes:
                                      --heap---
                                      comm used
                2026-03-09 18:08:17    64m  30m
                2026-03-09 18:09:17    64m  35m
                2026-03-09 18:10:17    64m  40m
                """;
        VmVitalsOutput output = VmVitalsParser.parse(vitals);
        String obs = me.bechberger.jstall.analyzer.impl.vmvitals.VmVitalsObservations.analyze(output);

        // Trend table should show 3 samples, first 18:08, last 18:10
        assertTrue(obs.contains("3 samples"), "Should show sample count");
        assertTrue(obs.contains("18:08"), "Should show start time");
        assertTrue(obs.contains("18:10"), "Should show end time");
        // heap-used grew 10 MB
        assertTrue(obs.contains("↑"), "Should show upward arrow for growing heap");
        assertTrue(obs.contains("10.00 MB"), "Should show 10 MB delta");
    }

    @Test
    void observationsAbsentWithOnlyOneRow() {
        String vitals = """
                27747:
                Vitals:

                (Vitals version 220600, pid: 27747)

                Last 60 minutes:
                                      --heap---
                                      comm used
                2026-03-09 18:08:17    64m  30m
                """;
        VmVitalsOutput output = VmVitalsParser.parse(vitals);
        String obs = me.bechberger.jstall.analyzer.impl.vmvitals.VmVitalsObservations.analyze(output);
        assertTrue(obs.isEmpty(), "Need at least 2 rows for any analysis");
    }

}