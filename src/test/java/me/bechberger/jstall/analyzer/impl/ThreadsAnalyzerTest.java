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

class ThreadsAnalyzerTest {

    @Test
    void testName() {
        ThreadsAnalyzer analyzer = new ThreadsAnalyzer();
        assertEquals("threads", analyzer.name());
    }

    @Test
    void testSupportedOptions() {
        ThreadsAnalyzer analyzer = new ThreadsAnalyzer();
        Set<String> supported = analyzer.supportedOptions();

        assertTrue(supported.contains("dumps"));
        assertTrue(supported.contains("interval"));
        assertTrue(supported.contains("keep"));
        assertTrue(supported.contains("no-native"));
        assertEquals(4, supported.size());
    }

    @Test
    void testDumpRequirement() {
        ThreadsAnalyzer analyzer = new ThreadsAnalyzer();
        assertEquals(DumpRequirement.MANY, analyzer.dumpRequirement());
    }

    @Test
    void testAnalyzeWithNoDumps() {
        ThreadsAnalyzer analyzer = new ThreadsAnalyzer();

        AnalyzerResult result = analyzer.analyze(List.of(), Map.of());

        assertEquals(0, result.exitCode());
        assertNotNull(result.output());
    }

    @Test
    void testActivityCategorizationInOutput() {
        ThreadsAnalyzer analyzer = new ThreadsAnalyzer();

        // Create thread dumps with different activities
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

        ThreadInfo networkThread = new ThreadInfo(
            "network-thread",
            2L,
            null,
            5,
            false,
            Thread.State.RUNNABLE,
            2.0,
            10.0,
            List.of(
                new StackFrame("sun.nio.ch.KQueue", "poll", null, null),
                new StackFrame("sun.nio.ch.KQueueSelectorImpl", "doSelect", "KQueueSelectorImpl.java", 125)
            ),
            List.of(),
            null,
            null
        );

        ThreadDump dump1 = new ThreadDump(
            Instant.now().minusSeconds(10),
            "Test Dump 1",
            List.of(ioThread, networkThread),
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

        ThreadInfo networkThread2 = new ThreadInfo(
            "network-thread",
            2L,
            null,
            5,
            false,
            Thread.State.RUNNABLE,
            3.0,
            20.0,
            List.of(
                new StackFrame("sun.nio.ch.KQueue", "poll", null, null),
                new StackFrame("sun.nio.ch.KQueueSelectorImpl", "doSelect", "KQueueSelectorImpl.java", 125)
            ),
            List.of(),
            null,
            null
        );

        ThreadDump dump2 = new ThreadDump(
            Instant.now(),
            "Test Dump 2",
            List.of(ioThread2, networkThread2),
            null,
            null,
            null
        );

        AnalyzerResult result = analyzer.analyzeThreadDumps(List.of(dump1, dump2), Map.of("top", 10));

        assertEquals(0, result.exitCode());
        String output = result.output();

        // Should contain activity categorization in the table
        assertTrue(output.contains("ACTIVITY"), "Output should contain 'ACTIVITY' column header");
        assertTrue(output.contains("I/O Read"), "Output should categorize I/O read activity");
        assertTrue(output.contains("Network"), "Output should categorize Network activity");
    }

    @Test
    void testTableFormat() {
        ThreadsAnalyzer analyzer = new ThreadsAnalyzer();

        ThreadInfo computeThread = new ThreadInfo(
            "compute-thread",
            1L,
            null,
            5,
            false,
            Thread.State.RUNNABLE,
            10.0,
            10.0,
            List.of(
                new StackFrame("com.example.MyApp", "compute", "MyApp.java", 100)
            ),
            List.of(),
            null,
            null
        );

        ThreadDump dump = new ThreadDump(
            Instant.now(),
            "Test Dump",
            List.of(computeThread),
            null,
            null,
            null
        );

        AnalyzerResult result = analyzer.analyzeThreadDumps(List.of(dump), Map.of());

        assertEquals(0, result.exitCode());
        String output = result.output();

        // Verify table structure includes all columns
        assertTrue(output.contains("THREAD"));
        assertTrue(output.contains("CPU TIME"));
        assertTrue(output.contains("CPU %"));
        assertTrue(output.contains("STATES"));
        assertTrue(output.contains("ACTIVITY"));
        assertTrue(output.contains("TOP STACK FRAME"));
    }
}