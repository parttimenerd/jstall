package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jthreaddump.model.StackFrame;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.model.ThreadInfo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MostWorkAnalyzerTest {

    @Test
    void testName() {
        MostWorkAnalyzer analyzer = new MostWorkAnalyzer();
        assertEquals("most-work", analyzer.name());
    }

    @Test
    void testSupportedOptions() {
        MostWorkAnalyzer analyzer = new MostWorkAnalyzer();
        Set<String> supported = analyzer.supportedOptions();

        assertTrue(supported.contains("dumps"));
        assertTrue(supported.contains("interval"));
        assertTrue(supported.contains("keep"));
        assertTrue(supported.contains("top"));
        assertTrue(supported.contains("no-native"));
        assertTrue(supported.contains("stack-depth"));
        assertEquals(6, supported.size());
    }

    @Test
    void testDumpRequirement() {
        MostWorkAnalyzer analyzer = new MostWorkAnalyzer();
        assertEquals(DumpRequirement.MANY, analyzer.dumpRequirement());
    }

    @Test
    void testAnalyzeWithNoDumps() {
        MostWorkAnalyzer analyzer = new MostWorkAnalyzer();

        // MANY requirement means empty list should work (but would fail in runner validation)
        AnalyzerResult result = analyzer.analyze(List.of(), Map.of());

        assertEquals(0, result.exitCode());
        assertNotNull(result.output());
    }

    @Test
    void testTopOption() {
        MostWorkAnalyzer analyzer = new MostWorkAnalyzer();

        // With no dumps, should still respect the top option
        AnalyzerResult result = analyzer.analyze(List.of(), Map.of("top", 5));

        assertEquals(0, result.exitCode());
        assertNotNull(result.output());
    }

    @Test
    void testActivityCategorizationInOutput() {
        MostWorkAnalyzer analyzer = new MostWorkAnalyzer();

        // Create thread dumps with I/O activity
        ThreadInfo ioThread = new ThreadInfo(
            "io-thread",
            1L,
            null,
            5,
            false,
            Thread.State.RUNNABLE,
            1.0,
            10.0,
            List.of(
                new StackFrame("java.io.FileInputStream", "read", "FileInputStream.java", 100)
            ),
            List.of(),
            null,
            null
        );

        ThreadDump dump1 = new ThreadDump(
            Instant.now().minusSeconds(10),
            "Test Dump 1",
            List.of(ioThread),
            null,
            null,
            null
        );

        ThreadInfo ioThread2 = new ThreadInfo(
            "io-thread",
            1L,
            null,
            5,
            false,
            Thread.State.RUNNABLE,
            2.0,
            20.0,
            List.of(
                new StackFrame("java.io.FileInputStream", "read", "FileInputStream.java", 100)
            ),
            List.of(),
            null,
            null
        );

        ThreadDump dump2 = new ThreadDump(
            Instant.now(),
            "Test Dump 2",
            List.of(ioThread2),
            null,
            null,
            null
        );

        AnalyzerResult result = analyzer.analyzeThreadDumps(List.of(dump1, dump2), Map.of("top", 3));

        assertEquals(0, result.exitCode());
        String output = result.output();

        // Should contain activity categorization
        assertTrue(output.contains("Activity:"), "Output should contain 'Activity:' label");
        assertTrue(output.contains("I/O Read"), "Output should categorize I/O read activity");
    }
}