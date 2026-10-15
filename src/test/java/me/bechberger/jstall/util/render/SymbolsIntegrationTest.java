package me.bechberger.jstall.util.render;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.analyzer.impl.*;
import me.bechberger.jstall.cli.BaseAnalyzerCommand;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jthreaddump.parser.ThreadDumpParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that every user-facing analyzer produces pure-ASCII output when
 * {@code unicode=false} is in the options map, and that the same output
 * contains the expected Unicode symbols when {@code unicode=true}.
 *
 * <p>Tests also cover the {@code --unicode auto/on/off} CLI flag.
 */
class SymbolsIntegrationTest {

    // ── Shared fixtures ──────────────────────────────────────────────────────

    private static final String MINIMAL_THREAD_DUMP = """
        2024-12-29 13:00:00
        Full thread dump Java HotSpot(TM) 64-Bit Server VM (21+35-2513 mixed mode):
        """;

    private static final String WAITING_THREAD_DUMP = """
        2024-12-29 13:00:00
        Full thread dump Java HotSpot(TM) 64-Bit Server VM (21+35-2513 mixed mode):

        "pool-1-thread-1" #42 daemon prio=5 os_prio=0 cpu=0.0ms elapsed=60.0s tid=0x00007f0000001000 nid=0x1234 waiting on condition [0x00007f0000002000]
           java.lang.Thread.State: WAITING (on object monitor)
        \tat java.lang.Object.wait(Native Method)
        \t- waiting on <0x00000000f41aab48> (a java.util.LinkedList)
        \tat java.lang.Object.wait(Object.java:338)
        \tat MyQueue.take(MyQueue.java:100)

        "pool-1-thread-2" #43 daemon prio=5 os_prio=0 cpu=0.0ms elapsed=60.0s tid=0x00007f0000003000 nid=0x1235 waiting on condition [0x00007f0000004000]
           java.lang.Thread.State: WAITING (on object monitor)
        \tat java.lang.Object.wait(Native Method)
        \t- waiting on <0x00000000f41aab48> (a java.util.LinkedList)
        \tat java.lang.Object.wait(Object.java:338)
        \tat MyQueue.take(MyQueue.java:100)
        """;

    // From VmVitalsAnalyzerTest — real SapMachine VM.vitals output with growing heap
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

        Last 60 minutes:
                              --------------------------------jvm---------------------------------
                              --heap--- ---------meta---------      --jthr--- --cldg-- ----cls----
                              comm used comm used csc csu gctr code num nd cr num anon num  ld uld
        2026-03-09 18:11:17    64m  56m  18m  18m  2m  2m  21m  10m  16  3  1  96   81 4785  2   0
        2026-03-09 18:10:17    64m  48m  17m  17m  2m  2m  21m  10m  15  3  0  95   80 4783  2   0
        2026-03-09 18:09:17    64m  40m  17m  17m  2m  2m  21m  10m  15  3  0  95   80 4781  1   0
        2026-03-09 18:08:17    64m  30m  17m  17m  2m  2m  21m  10m  15  3  0  95   80 4780  0   0
        """;

    private static final String SAMPLE_VM_CLASSLOADER_STATS = """
        7651:
        ClassLoader         Parent              CLD*               Classes   ChunkSz   BlockSz  Type
        0x00000090010b2e50  0x0000009000069008  0x0000000aae00bf20      18    141312    119848  org.eclipse.osgi.internal.loader.EquinoxClassLoader
        0x00000090000976d8  0x0000000000000000  0x0000000aadccb7a0       1      4096      3176  jdk.internal.reflect.DelegatingClassLoader
        0x00000090010b2e50  0x0000009000069008  0x0000000aadc3a260      34    187392    155984  org.eclipse.osgi.internal.loader.EquinoxClassLoader
        """;

    // Two samples with different class counts — gives a trend arrow
    private static final String SAMPLE_VM_CLASSLOADER_STATS_2 = """
        7651:
        ClassLoader         Parent              CLD*               Classes   ChunkSz   BlockSz  Type
        0x00000090010b2e50  0x0000009000069008  0x0000000aae00bf20      30    200000    180000  org.eclipse.osgi.internal.loader.EquinoxClassLoader
        0x00000090000976d8  0x0000000000000000  0x0000000aadccb7a0       5      8000      6000  jdk.internal.reflect.DelegatingClassLoader
        """;

    private static final String SAMPLE_VM_METASPACE_A = """
        5785:

        Total Usage - 262 loaders, 6009 classes (1383 shared):
          Non-Class: 1199 chunks,     27.38 MB capacity,   27.00 MB ( 99%) committed,    26.55 MB ( 97%) used,   457.27 KB (  2%) free,     1.00 KB ( <1%) waste , deallocated: 39 blocks with 6.48 KB
              Class:  495 chunks,      3.42 MB capacity,    3.30 MB ( 96%) committed,     2.96 MB ( 86%) used,   349.73 KB ( 10%) free,    40 bytes ( <1%) waste , deallocated: 205 blocks with 55.79 KB
               Both: 1694 chunks,     30.80 MB capacity,   30.30 MB ( 98%) committed,    29.51 MB ( 96%) used,   806.99 KB (  3%) free,     1.04 KB ( <1%) waste , deallocated: 244 blocks with 62.27 KB

        Virtual space:
          Non-class space:       64.00 MB reserved,      27.00 MB ( 42%) committed,  1 nodes.
              Class space:        1.00 GB reserved,       3.31 MB ( <1%) committed,  1 nodes.
                     Both:        1.06 GB reserved,      30.31 MB (  3%) committed.
        """;

    private static final String SAMPLE_VM_METASPACE_B = """
        5785:

        Total Usage - 270 loaders, 6200 classes (1383 shared):
          Non-Class: 1250 chunks,     28.00 MB capacity,   27.80 MB ( 99%) committed,    27.50 MB ( 98%) used,   300.00 KB (  1%) free,     1.00 KB ( <1%) waste , deallocated: 40 blocks with 7.00 KB
              Class:  510 chunks,      3.60 MB capacity,    3.50 MB ( 97%) committed,     3.10 MB ( 86%) used,   400.00 KB ( 11%) free,    40 bytes ( <1%) waste , deallocated: 210 blocks with 56.00 KB
               Both: 1760 chunks,     31.60 MB capacity,   31.30 MB ( 99%) committed,    30.60 MB ( 97%) used,   700.00 KB (  2%) free,     1.04 KB ( <1%) waste , deallocated: 250 blocks with 63.00 KB

        Virtual space:
          Non-class space:       64.00 MB reserved,      27.80 MB ( 43%) committed,  1 nodes.
              Class space:        1.00 GB reserved,       3.51 MB ( <1%) committed,  1 nodes.
                     Both:        1.06 GB reserved,      31.31 MB (  3%) committed.
        """;

    private static final String GC_HEAP_INFO_SAMPLE = """
        3212:
        Heap Configuration:
         MinHeapFreeRatio         = 40
         MaxHeapFreeRatio         = 70
         MaxHeapSize              = 4294967296 (4096.0MB)
         GC.heap_info
        heap total 524288K, used 131072K
        Metaspace       used 45000K, committed 46080K, reserved 1130496K
         class space    used 6000K, committed 6144K, reserved 1048576K
        """;

    private static final String GC_HEAP_INFO_SAMPLE_2 = """
        3212:
        heap total 524288K, used 200000K
        Metaspace       used 50000K, committed 51200K, reserved 1130496K
         class space    used 7000K, committed 7168K, reserved 1048576K
        """;

    private static final String HISTOGRAM_BEFORE = """
        num     #instances         #bytes  class name (module)
        -------------------------------------------------------
           1:          1000         100000  com.example.Foo (app)
           2:          2000         200000  com.example.Bar (app)
        """;

    private static final String HISTOGRAM_AFTER = """
        num     #instances         #bytes  class name (module)
        -------------------------------------------------------
           1:          1500         200000  com.example.Foo (app)
           2:          2000         200000  com.example.Bar (app)
        """;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ThreadDumpSnapshot parseDump(String content) {
        try {
            return new ThreadDumpSnapshot(ThreadDumpParser.parse(content), content, null, Map.of());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void assertPureAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            assertTrue(c <= 127,
                String.format("non-ASCII char U+%04X '%s' at index %d near: ...%s...",
                    (int) c, c, i,
                    s.substring(Math.max(0, i - 20), Math.min(s.length(), i + 21))));
        }
    }

    // ── VmVitalsAnalyzer ──────────────────────────────────────────────────────

    @Test
    void vmVitals_unicodeFalse_isPureAscii() {
        VmVitalsAnalyzer analyzer = new VmVitalsAnalyzer();
        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(
            List.of(parseDump(WAITING_THREAD_DUMP)),
            Map.of("vm-vitals", List.of(new CollectedData(1L, SAMPLE_VM_VITALS, Map.of())))
        );
        String output = analyzer.analyze(data, Map.of("unicode", false)).output();
        assertPureAscii(output);
    }

    @Test
    void vmVitals_unicodeFalse_noUnicodeArrows() {
        VmVitalsAnalyzer analyzer = new VmVitalsAnalyzer();
        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(
            List.of(parseDump(WAITING_THREAD_DUMP)),
            Map.of("vm-vitals", List.of(new CollectedData(1L, SAMPLE_VM_VITALS, Map.of())))
        );
        String output = analyzer.analyze(data, Map.of("unicode", false)).output();
        assertFalse(output.contains("↑"), "↑ must not appear in ASCII mode");
        assertFalse(output.contains("↓"), "↓ must not appear in ASCII mode");
        assertFalse(output.contains("→"), "→ must not appear in ASCII mode");
        assertFalse(output.contains("–"), "– must not appear in ASCII mode");
        assertFalse(output.contains("—"), "— must not appear in ASCII mode");
        assertFalse(output.contains("Δ"), "Δ must not appear in ASCII mode");
    }

    @Test
    void vmVitals_unicodeTrue_containsUnicodeChars() {
        VmVitalsAnalyzer analyzer = new VmVitalsAnalyzer();
        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(
            List.of(parseDump(WAITING_THREAD_DUMP)),
            Map.of("vm-vitals", List.of(new CollectedData(1L, SAMPLE_VM_VITALS, Map.of())))
        );
        String output = analyzer.analyze(data, Map.of("unicode", true)).output();
        // Growing heap-used over 4 samples → should see ↑ or → arrow
        assertTrue(output.contains("↑") || output.contains("→") || output.contains("–"),
            "Unicode mode should contain Unicode chars, got:\n" + output);
    }

    @Test
    void vmVitals_trendsHeader_unicodeFalse_usesAsciiArrow() {
        VmVitalsAnalyzer analyzer = new VmVitalsAnalyzer();
        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(
            List.of(parseDump(WAITING_THREAD_DUMP)),
            Map.of("vm-vitals", List.of(new CollectedData(1L, SAMPLE_VM_VITALS, Map.of())))
        );
        String output = analyzer.analyze(data, Map.of("unicode", false)).output();
        if (output.contains("Trends")) {
            String trendsLine = output.lines().filter(l -> l.startsWith("Trends")).findFirst().orElse("");
            assertFalse(trendsLine.contains("→"), "Trends header must not contain → in ASCII mode");
            assertTrue(trendsLine.contains("->"), "Trends header should use -> in ASCII mode");
        }
    }

    // ── WaitingThreadsAnalyzer ────────────────────────────────────────────────

    @Test
    void waitingThreads_unicodeFalse_isPureAscii() {
        WaitingThreadsAnalyzer analyzer = new WaitingThreadsAnalyzer();
        ResolvedData data = ResolvedData.fromDumps(List.of(
            parseDump(WAITING_THREAD_DUMP), parseDump(WAITING_THREAD_DUMP)));
        String output = analyzer.analyze(data, Map.of("unicode", false)).output();
        assertPureAscii(output);
    }

    @Test
    void waitingThreads_unicodeFalse_noUnicodeChars() {
        WaitingThreadsAnalyzer analyzer = new WaitingThreadsAnalyzer();
        ResolvedData data = ResolvedData.fromDumps(List.of(
            parseDump(WAITING_THREAD_DUMP), parseDump(WAITING_THREAD_DUMP)));
        String output = analyzer.analyze(data, Map.of("unicode", false)).output();
        assertFalse(output.contains("→"), "→ must not appear in ASCII mode");
        assertFalse(output.contains("•"), "• must not appear in ASCII mode");
        assertFalse(output.contains("─"), "─ must not appear in ASCII mode");
    }

    @Test
    void waitingThreads_unicodeTrue_containsUnicodeChars() {
        WaitingThreadsAnalyzer analyzer = new WaitingThreadsAnalyzer();
        ResolvedData data = ResolvedData.fromDumps(List.of(
            parseDump(WAITING_THREAD_DUMP), parseDump(WAITING_THREAD_DUMP)));
        String output = analyzer.analyze(data, Map.of("unicode", true)).output();
        assertTrue(output.contains("→") || output.contains("•") || output.contains("─"),
            "Unicode mode should contain Unicode chars, got:\n" + output);
    }

    // ── VmClassloaderStatsAnalyzer ────────────────────────────────────────────

    @Test
    void vmClassloaderStats_unicodeFalse_isPureAscii() {
        VmClassloaderStatsAnalyzer analyzer = new VmClassloaderStatsAnalyzer();
        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(
            List.of(parseDump(MINIMAL_THREAD_DUMP)),
            Map.of("vm-classloader-stats", List.of(
                new CollectedData(1L, SAMPLE_VM_CLASSLOADER_STATS, Map.of()),
                new CollectedData(2L, SAMPLE_VM_CLASSLOADER_STATS_2, Map.of())))
        );
        String output = analyzer.analyze(data, Map.of("unicode", false)).output();
        assertPureAscii(output);
    }

    @Test
    void vmClassloaderStats_unicodeFalse_noUnicodeArrows() {
        VmClassloaderStatsAnalyzer analyzer = new VmClassloaderStatsAnalyzer();
        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(
            List.of(parseDump(MINIMAL_THREAD_DUMP)),
            Map.of("vm-classloader-stats", List.of(
                new CollectedData(1L, SAMPLE_VM_CLASSLOADER_STATS, Map.of()),
                new CollectedData(2L, SAMPLE_VM_CLASSLOADER_STATS_2, Map.of())))
        );
        String output = analyzer.analyze(data, Map.of("unicode", false)).output();
        assertFalse(output.contains("↑"), "↑ must not appear in ASCII mode");
        assertFalse(output.contains("↓"), "↓ must not appear in ASCII mode");
        assertFalse(output.contains("→"), "→ must not appear in ASCII mode");
    }

    @Test
    void vmClassloaderStats_unicodeTrue_containsArrows() {
        VmClassloaderStatsAnalyzer analyzer = new VmClassloaderStatsAnalyzer();
        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(
            List.of(parseDump(MINIMAL_THREAD_DUMP)),
            Map.of("vm-classloader-stats", List.of(
                new CollectedData(1L, SAMPLE_VM_CLASSLOADER_STATS, Map.of()),
                new CollectedData(2L, SAMPLE_VM_CLASSLOADER_STATS_2, Map.of())))
        );
        String output = analyzer.analyze(data, Map.of("unicode", true)).output();
        // Class counts differ between samples → trend arrow should appear
        assertTrue(output.contains("↑") || output.contains("↓") || output.contains("→"),
            "Unicode mode should contain trend arrows, got:\n" + output);
    }

    // ── VmMetaspaceAnalyzer ───────────────────────────────────────────────────

    @Test
    void vmMetaspace_unicodeFalse_isPureAscii() {
        VmMetaspaceAnalyzer analyzer = new VmMetaspaceAnalyzer();
        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(
            List.of(parseDump(MINIMAL_THREAD_DUMP)),
            Map.of("vm-metaspace", List.of(
                new CollectedData(1L, SAMPLE_VM_METASPACE_A, Map.of()),
                new CollectedData(2L, SAMPLE_VM_METASPACE_B, Map.of())))
        );
        String output = analyzer.analyze(data, Map.of("unicode", false)).output();
        assertPureAscii(output);
    }

    @Test
    void vmMetaspace_unicodeFalse_noUnicodeArrows() {
        VmMetaspaceAnalyzer analyzer = new VmMetaspaceAnalyzer();
        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(
            List.of(parseDump(MINIMAL_THREAD_DUMP)),
            Map.of("vm-metaspace", List.of(
                new CollectedData(1L, SAMPLE_VM_METASPACE_A, Map.of()),
                new CollectedData(2L, SAMPLE_VM_METASPACE_B, Map.of())))
        );
        String output = analyzer.analyze(data, Map.of("unicode", false)).output();
        assertFalse(output.contains("↑"), "↑ must not appear in ASCII mode");
        assertFalse(output.contains("↓"), "↓ must not appear in ASCII mode");
        assertFalse(output.contains("→"), "→ must not appear in ASCII mode");
    }

    @Test
    void vmMetaspace_unicodeTrue_containsArrows() {
        VmMetaspaceAnalyzer analyzer = new VmMetaspaceAnalyzer();
        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(
            List.of(parseDump(MINIMAL_THREAD_DUMP)),
            Map.of("vm-metaspace", List.of(
                new CollectedData(1L, SAMPLE_VM_METASPACE_A, Map.of()),
                new CollectedData(2L, SAMPLE_VM_METASPACE_B, Map.of())))
        );
        String output = analyzer.analyze(data, Map.of("unicode", true)).output();
        assertTrue(output.contains("↑") || output.contains("↓") || output.contains("→"),
            "Unicode mode should contain trend arrows, got:\n" + output);
    }

    // ── GcHeapInfoAnalyzer ────────────────────────────────────────────────────

    @Test
    void gcHeapInfo_unicodeFalse_isPureAscii() {
        GcHeapInfoAnalyzer analyzer = new GcHeapInfoAnalyzer();
        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(
            List.of(parseDump(MINIMAL_THREAD_DUMP)),
            Map.of("gc-heap-info", List.of(
                new CollectedData(1L, GC_HEAP_INFO_SAMPLE, Map.of()),
                new CollectedData(2L, GC_HEAP_INFO_SAMPLE_2, Map.of())))
        );
        String output = analyzer.analyze(data, Map.of("unicode", false)).output();
        if (!output.isBlank()) {
            assertPureAscii(output);
        }
    }

    @Test
    void gcHeapInfo_unicodeFalse_noDeltaSymbol() {
        GcHeapInfoAnalyzer analyzer = new GcHeapInfoAnalyzer();
        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(
            List.of(parseDump(MINIMAL_THREAD_DUMP)),
            Map.of("gc-heap-info", List.of(
                new CollectedData(1L, GC_HEAP_INFO_SAMPLE, Map.of()),
                new CollectedData(2L, GC_HEAP_INFO_SAMPLE_2, Map.of())))
        );
        String output = analyzer.analyze(data, Map.of("unicode", false)).output();
        if (!output.isBlank()) {
            assertFalse(output.contains("Δ"), "Δ must not appear in ASCII mode");
        }
    }

    @Test
    void gcHeapInfo_unicodeTrue_containsDeltaSymbol() {
        GcHeapInfoAnalyzer analyzer = new GcHeapInfoAnalyzer();
        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(
            List.of(parseDump(MINIMAL_THREAD_DUMP)),
            Map.of("gc-heap-info", List.of(
                new CollectedData(1L, GC_HEAP_INFO_SAMPLE, Map.of()),
                new CollectedData(2L, GC_HEAP_INFO_SAMPLE_2, Map.of())))
        );
        String output = analyzer.analyze(data, Map.of("unicode", true)).output();
        if (!output.isBlank()) {
            assertTrue(output.contains("Δ"), "Unicode mode should contain Δ column header, got:\n" + output);
        }
    }

    // ── ClassHistogramDiffAnalyzer ────────────────────────────────────────────

    @Test
    void classHistogramDiff_unicodeFalse_isPureAscii() {
        ClassHistogramDiffAnalyzer analyzer = new ClassHistogramDiffAnalyzer();
        ResolvedData data = new ResolvedData(
            List.of(), Map.of(), null,
            Map.of("gc-class-histogram", List.of(
                new CollectedData(1L, HISTOGRAM_BEFORE, Map.of()),
                new CollectedData(2L, HISTOGRAM_AFTER, Map.of())))
        );
        String output = analyzer.analyze(data, Map.of("unicode", false, "top", 5)).output();
        assertPureAscii(output);
    }

    @Test
    void classHistogramDiff_unicodeFalse_noDeltaSymbol() {
        ClassHistogramDiffAnalyzer analyzer = new ClassHistogramDiffAnalyzer();
        ResolvedData data = new ResolvedData(
            List.of(), Map.of(), null,
            Map.of("gc-class-histogram", List.of(
                new CollectedData(1L, HISTOGRAM_BEFORE, Map.of()),
                new CollectedData(2L, HISTOGRAM_AFTER, Map.of())))
        );
        String output = analyzer.analyze(data, Map.of("unicode", false, "top", 5)).output();
        assertFalse(output.contains("Δ"), "Δ must not appear in ASCII mode, got:\n" + output);
    }

    @Test
    void classHistogramDiff_unicodeTrue_containsDeltaSymbol() {
        ClassHistogramDiffAnalyzer analyzer = new ClassHistogramDiffAnalyzer();
        ResolvedData data = new ResolvedData(
            List.of(), Map.of(), null,
            Map.of("gc-class-histogram", List.of(
                new CollectedData(1L, HISTOGRAM_BEFORE, Map.of()),
                new CollectedData(2L, HISTOGRAM_AFTER, Map.of())))
        );
        String output = analyzer.analyze(data, Map.of("unicode", true, "top", 5)).output();
        assertTrue(output.contains("Δ"), "Unicode mode should contain Δ column header, got:\n" + output);
    }

    // ── --unicode CLI flag: BaseAnalyzerCommand.resolveUnicode() ─────────────

    @Test
    void resolveUnicode_off_returnsFalse() {
        assertFalse(new StubCmd("off").resolveUnicode());
    }

    @Test
    void resolveUnicode_on_returnsTrue() {
        assertTrue(new StubCmd("on").resolveUnicode());
    }

    @Test
    void resolveUnicode_auto_matchesIsUnicodeCapable() {
        assertEquals(BaseAnalyzerCommand.isUnicodeCapable(), new StubCmd("auto").resolveUnicode());
    }

    @Test
    void resolveUnicode_caseInsensitive_OFF() {
        assertFalse(new StubCmd("OFF").resolveUnicode());
    }

    @Test
    void resolveUnicode_caseInsensitive_ON() {
        assertTrue(new StubCmd("ON").resolveUnicode());
    }

    @Test
    void resolveUnicode_caseInsensitive_AUTO() {
        assertEquals(BaseAnalyzerCommand.isUnicodeCapable(), new StubCmd("AUTO").resolveUnicode());
    }

    // ── Minimal BaseAnalyzerCommand stub ─────────────────────────────────────

    private static class StubCmd extends BaseAnalyzerCommand {
        StubCmd(String mode) {
            this.unicodeMode = mode;
        }

        @Override
        protected me.bechberger.jstall.analyzer.Analyzer getAnalyzer() {
            throw new UnsupportedOperationException("stub");
        }
    }
}
