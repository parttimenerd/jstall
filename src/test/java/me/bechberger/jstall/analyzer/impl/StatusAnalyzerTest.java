package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jstall.provider.requirement.DataRequirement;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.parser.ThreadDumpParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class StatusAnalyzerTest {

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

    private static final String SAMPLE_VM_METASPACE = """
        5785:

        Total Usage - 262 loaders, 6009 classes (1383 shared):
          Non-Class: 1199 chunks,     27.38 MB capacity,   27.00 MB ( 99%) committed,    26.55 MB ( 97%) used,   457.27 KB (  2%) free,     1.00 KB ( <1%) waste
              Class:  495 chunks,      3.42 MB capacity,    3.30 MB ( 96%) committed,     2.96 MB ( 86%) used,   349.73 KB ( 10%) free,    40 bytes ( <1%) waste
               Both: 1694 chunks,     30.80 MB capacity,   30.30 MB ( 98%) committed,    29.51 MB ( 96%) used,   806.99 KB (  3%) free,     1.04 KB ( <1%) waste

        Virtual space:
          Non-class space:       64.00 MB reserved,      27.00 MB ( 42%) committed,  1 nodes.
              Class space:        1.00 GB reserved,       3.31 MB ( <1%) committed,  1 nodes.
                     Both:        1.06 GB reserved,      30.31 MB (  3%) committed.

        Settings:
        MaxMetaspaceSize: unlimited
        CompressedClassSpaceSize: 1.00 GB
        Initial GC threshold: 21.00 MB
        Current GC threshold: 35.00 MB
        CDS: on
        """;

    private static final String SAMPLE_VM_CLASSLOADER_STATS = """
        7651:
        ClassLoader         Parent              CLD*               Classes   ChunkSz   BlockSz  Type
        0x00000090010b2e50  0x0000009000069008  0x0000000aae00bf20      18    141312    119848  org.eclipse.osgi.internal.loader.EquinoxClassLoader
        0x00000090000976d8  0x0000000000000000  0x0000000aadccb7a0       1      4096      3176  jdk.internal.reflect.DelegatingClassLoader
        0x00000090010b2e50  0x0000009000069008  0x0000000aadc3a260      34    187392    155984  org.eclipse.osgi.internal.loader.EquinoxClassLoader
        """;

    private static final String SAMPLE_COMPILER_QUEUE = """
        Current compiles:

        C1 compile queue:
        Empty

        C2 compile queue:
        Empty
        """;

    // Helper method to create minimal test dumps
    private List<ThreadDumpSnapshot> createTestDumps(int count) throws IOException {
        // Create minimal thread dump content
        String dumpContent = """
            2024-12-29 13:00:00
            Full thread dump Java HotSpot(TM) 64-Bit Server VM (21+35-2513 mixed mode):
            
            "main" #1 prio=5 os_prio=0 tid=0x00007f8b0c00b800 nid=0x1 runnable [0x00007f8b14e5d000]
               java.lang.Thread.State: RUNNABLE
            	at java.lang.Object.wait(java.base@21/Native Method)
            """;

        ThreadDump parsed = ThreadDumpParser.parse(dumpContent);
        return List.of(
            new ThreadDumpSnapshot(parsed, dumpContent, null, null),
            new ThreadDumpSnapshot(parsed, dumpContent, null, null)
        ).subList(0, Math.min(count, 2));
    }

    @Test
    void testName() {
        StatusAnalyzer analyzer = new StatusAnalyzer();
        assertEquals("status", analyzer.name());
    }

    @Test
    void testSupportedOptions() {
        StatusAnalyzer analyzer = new StatusAnalyzer();
        Set<String> supported = analyzer.supportedOptions();

        // Should include options from DeadLockAnalyzer
        assertTrue(supported.contains("keep"));

        // Should include options from MostWorkAnalyzer
        assertTrue(supported.contains("top"));
        assertTrue(supported.contains("dumps"));
        assertTrue(supported.contains("interval"));

        // Verify it's the union of both analyzers' options
        assertTrue(supported.size() >= 4);
    }

    @Test
    void testDumpRequirement() {
        StatusAnalyzer analyzer = new StatusAnalyzer();
        // StatusAnalyzer requires MANY because MostWorkAnalyzer requires MANY
        assertEquals(DumpRequirement.MANY, analyzer.dumpRequirement());
    }

    @Test
    void testAnalyzeWithMinimalDumps() throws IOException {
        StatusAnalyzer analyzer = new StatusAnalyzer();
        Map<String, Object> options = Map.of(
            "keep", false,
            "top", 3,
            "dumps", 3,
            "interval", "5s"
        );

        // Use at least 2 dumps since MostWorkAnalyzer requires MANY
        List<ThreadDumpSnapshot> dumps = createTestDumps(2);
        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(dumps), options);

        // Should return result from constituent analyzers
        assertEquals(0, result.exitCode());
        assertNotNull(result.output());
    }

    @Test
    void testGetDataRequirementsIncludesAllConstituentAnalyzerContributions() {
        StatusAnalyzer analyzer = new StatusAnalyzer();

        List<Analyzer> expectedContributors = List.of(
            new VmVitalsAnalyzer(),
            new GcHeapInfoAnalyzer(),
            new VmClassloaderStatsAnalyzer(),
            new VmMetaspaceAnalyzer(),
            new CompilerQueueAnalyzer(),
            new DeadLockAnalyzer(),
            new MostWorkAnalyzer(),
            new ThreadsAnalyzer(),
            new DependencyTreeAnalyzer(),
            new SystemProcessAnalyzer(),
            new JvmSupportAnalyzer()
        );

        Set<String> expectedRequirementTypes = expectedContributors.stream()
            .flatMap(contributor -> contributor.getDataRequirements(Map.of(
                    "dumps", 2,
                    "interval", 5000L
                )).getRequirements().stream())
            .map(DataRequirement::getType)
            .collect(Collectors.toSet());
        expectedRequirementTypes.add("vm-uptime");

        Set<String> requirementTypes = analyzer.getDataRequirements(Map.of(
                "dumps", 2,
                "interval", 5000L
            )).getRequirements().stream()
            .map(DataRequirement::getType)
            .collect(Collectors.toSet());

        assertEquals(expectedRequirementTypes, requirementTypes);
    }

    @Test
    void testAnalyzeShowsAllRelevantSectionsWhenDataIsAvailable() throws IOException {
        StatusAnalyzer analyzer = new StatusAnalyzer();
        List<ThreadDumpSnapshot> dumps = createTestDumps(2);

        ResolvedData data = new ResolvedData(
            dumps,
            Map.of(
                "java.version.date", "2025-10-21",
                "java.vendor", "JetBrains s.r.o.",
                "java.version", "21.0.9",
                "java.vendor.version", "JBR-21.0.9+10-1038.76-jcef"
            ),
            null,
            Map.of(
                "vm-uptime", List.of(new CollectedData(5L, "3183:\n123.456 s", Map.of())),
                "gc-heap-info", List.of(
                    new CollectedData(1L, SAMPLE_GC_HEAP_INFO_PREVIOUS, Map.of()),
                    new CollectedData(2L, SAMPLE_GC_HEAP_INFO_LATEST, Map.of())
                ),
                "vm-classloader-stats", List.of(new CollectedData(3L, SAMPLE_VM_CLASSLOADER_STATS, Map.of())),
                "vm-metaspace", List.of(new CollectedData(4L, SAMPLE_VM_METASPACE, Map.of())),
                "compiler-queue", List.of(new CollectedData(5L, SAMPLE_COMPILER_QUEUE, Map.of()))
            )
        );

        AnalyzerResult result = analyzer.analyze(data, Map.of(
            "keep", false,
            "top", 3,
            "dumps", 2,
            "interval", 5000L
        ));

        String output = result.output();

        assertTrue(output.contains("VM uptime: 123.456 s"));
        assertTrue(output.contains("=== most-work ==="));
        assertTrue(output.contains("=== threads ==="));
        assertTrue(output.contains("=== gc-heap-info ==="));
        assertTrue(output.contains("=== vm-classloader-stats ==="));
        assertTrue(output.contains("=== vm-metaspace ==="));
        assertTrue(output.contains("=== compiler-queue ==="));
        assertTrue(output.contains("=== jvm-support ==="));
    }

    @Test
    void testAnalyzeCombinesResults() throws IOException {
        StatusAnalyzer analyzer = new StatusAnalyzer();
        Map<String, Object> options = Map.of(
            "keep", false,
            "top", 3
        );

        // Use at least 2 dumps
        List<ThreadDumpSnapshot> dumps = createTestDumps(2);
        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(dumps), options);

        // Output should contain sections from both analyzers
        String output = result.output();

        // Should have sections from both analyzers
        assertTrue(output.contains("deadlock") || output.contains("most-work"));
    }

    @Test
    void testAnalyzeFiltersOptions() throws IOException {
        StatusAnalyzer analyzer = new StatusAnalyzer();

        // Pass options that only some analyzers support
        Map<String, Object> options = Map.of(
            "top", 5,  // Only for MostWorkAnalyzer
            "keep", false
        );

        List<ThreadDumpSnapshot> dumps = createTestDumps(2);

        // Should not throw exception even though some options aren't used by all analyzers
        assertDoesNotThrow(() -> {
            AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(dumps), options);
            assertNotNull(result);
        });
    }


    @Test
    void testExitCodePropagation() throws IOException {
        // This test would require creating actual ThreadDumps with deadlock info
        // to verify that a non-zero exit code from DeadLockAnalyzer is propagated
        // For now, we test the basic structure
        StatusAnalyzer analyzer = new StatusAnalyzer();
        Map<String, Object> options = Map.of("keep", false);

        List<ThreadDumpSnapshot> dumps = createTestDumps(2);
        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(dumps), options);

        // With minimal dumps, exit code should be 0
        assertEquals(0, result.exitCode());
    }

    @Test
    void testShowsVmUptimeWhenAvailable() throws IOException {
        StatusAnalyzer analyzer = new StatusAnalyzer();
        List<ThreadDumpSnapshot> dumps = createTestDumps(2);

        ResolvedData data = new ResolvedData(
            dumps,
            Map.of(),
            null,
            Map.of("vm-uptime", List.of(new CollectedData(System.currentTimeMillis(), "3183:\n123.456 s", Map.of())))
        );

        AnalyzerResult result = analyzer.analyze(data, Map.of("keep", false));

        assertTrue(result.output().contains("VM uptime: 123.456 s"));
    }
}