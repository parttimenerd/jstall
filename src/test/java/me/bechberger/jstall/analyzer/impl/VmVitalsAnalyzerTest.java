package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
import me.bechberger.jthreaddump.parser.ThreadDumpParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.util.List;
import java.util.Map;

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
        assertTrue(output.contains("VM Vitals:"), "Should contain VM Vitals header");
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
        assertTrue(output.contains("VM Vitals:"));
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
}