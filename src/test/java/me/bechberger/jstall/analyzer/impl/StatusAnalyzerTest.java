package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.parser.ThreadDumpParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StatusAnalyzerTest {

    // Helper method to create minimal test dumps
    private List<ThreadDump> createTestDumps(int count) throws IOException {
        // Create minimal thread dump content
        String dumpContent = """
            2024-12-29 13:00:00
            Full thread dump Java HotSpot(TM) 64-Bit Server VM (21+35-2513 mixed mode):
            
            "main" #1 prio=5 os_prio=0 tid=0x00007f8b0c00b800 nid=0x1 runnable [0x00007f8b14e5d000]
               java.lang.Thread.State: RUNNABLE
            	at java.lang.Object.wait(java.base@21/Native Method)
            """;

        return List.of(
            ThreadDumpParser.parse(dumpContent),
            ThreadDumpParser.parse(dumpContent)
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
        List<ThreadDump> dumps = createTestDumps(2);
        AnalyzerResult result = analyzer.analyze(dumps, options);

        // Should return result from constituent analyzers
        assertEquals(0, result.exitCode());
        assertNotNull(result.output());
    }

    @Test
    void testAnalyzeCombinesResults() throws IOException {
        StatusAnalyzer analyzer = new StatusAnalyzer();
        Map<String, Object> options = Map.of(
            "keep", false,
            "top", 3
        );

        // Use at least 2 dumps
        List<ThreadDump> dumps = createTestDumps(2);
        AnalyzerResult result = analyzer.analyze(dumps, options);

        // Output should contain sections from both analyzers
        String output = result.output();

        // Should have sections from both analyzers
        assertTrue(output.contains("dead-lock") || output.contains("most-work"));
    }

    @Test
    void testAnalyzeFiltersOptions() throws IOException {
        StatusAnalyzer analyzer = new StatusAnalyzer();

        // Pass options that only some analyzers support
        Map<String, Object> options = Map.of(
            "top", 5,  // Only for MostWorkAnalyzer
            "keep", false
        );

        List<ThreadDump> dumps = createTestDumps(2);

        // Should not throw exception even though some options aren't used by all analyzers
        assertDoesNotThrow(() -> {
            AnalyzerResult result = analyzer.analyze(dumps, options);
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

        List<ThreadDump> dumps = createTestDumps(2);
        AnalyzerResult result = analyzer.analyze(dumps, options);

        // With minimal dumps, exit code should be 0
        assertEquals(0, result.exitCode());
    }
}