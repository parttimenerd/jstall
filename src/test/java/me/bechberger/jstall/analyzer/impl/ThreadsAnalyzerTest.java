package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerOutput;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.Cell;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.analyzer.TableModel;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
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

        assertTrue(supported.contains("dump-count"));
        assertTrue(supported.contains("interval"));
        assertTrue(supported.contains("keep"));
        assertTrue(supported.contains("no-native"));
        assertTrue(supported.contains("top"));
        assertEquals(5, supported.size());
    }

    @Test
    void testDumpRequirement() {
        ThreadsAnalyzer analyzer = new ThreadsAnalyzer();
        assertEquals(DumpRequirement.MANY, analyzer.dumpRequirement());
    }

    @Test
    void testAnalyzeWithNoDumps() {
        ThreadsAnalyzer analyzer = new ThreadsAnalyzer();

        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(List.of()), Map.of());

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

        ThreadDumpSnapshot snapshot1 = new ThreadDumpSnapshot(dump1, "", null, null);
        ThreadDumpSnapshot snapshot2 = new ThreadDumpSnapshot(dump2, "", null, null);
        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(List.of(snapshot1, snapshot2)), Map.of("top", 10));

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

        ThreadDumpSnapshot snapshot = new ThreadDumpSnapshot(dump, "", null, null);
        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(List.of(snapshot)), Map.of());

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

    @Test
    void testCpuTimeFormattingMilliseconds() {
        ThreadsAnalyzer analyzer = new ThreadsAnalyzer();

        ThreadInfo thread1 = new ThreadInfo(
            "fast-thread", 1L, null, 5, false, Thread.State.RUNNABLE,
            1.000, 10.0,
            List.of(new StackFrame("com.example.App", "run", "App.java", 10)),
            List.of(), null, null
        );
        ThreadInfo thread2 = new ThreadInfo(
            "fast-thread", 1L, null, 5, false, Thread.State.RUNNABLE,
            1.003, 10.0,
            List.of(new StackFrame("com.example.App", "run", "App.java", 10)),
            List.of(), null, null
        );

        ThreadDump dump1 = new ThreadDump(Instant.now().minusSeconds(5), "d1",
            List.of(thread1), null, null, null);
        ThreadDump dump2 = new ThreadDump(Instant.now(), "d2",
            List.of(thread2), null, null, null);

        ThreadDumpSnapshot s1 = new ThreadDumpSnapshot(dump1, "", null, null);
        ThreadDumpSnapshot s2 = new ThreadDumpSnapshot(dump2, "", null, null);
        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(List.of(s1, s2)), Map.of());

        String output = result.output();
        assertTrue(output.contains("ms"), "Small CPU time should be in ms format, got: " + output);
        assertFalse(output.contains("0.00s"), "Should not show 0.00s for non-zero CPU time: " + output);
    }

    @Test
    void testCpuTimeFormattingSeconds() {
        ThreadsAnalyzer analyzer = new ThreadsAnalyzer();

        ThreadInfo thread1 = new ThreadInfo(
            "compute-thread", 1L, null, 5, false, Thread.State.RUNNABLE,
            1.0, 10.0,
            List.of(new StackFrame("com.example.App", "compute", "App.java", 10)),
            List.of(), null, null
        );
        ThreadInfo thread2 = new ThreadInfo(
            "compute-thread", 1L, null, 5, false, Thread.State.RUNNABLE,
            2.5, 20.0,
            List.of(new StackFrame("com.example.App", "compute", "App.java", 10)),
            List.of(), null, null
        );

        ThreadDump dump1 = new ThreadDump(Instant.now().minusSeconds(10), "d1",
            List.of(thread1), null, null, null);
        ThreadDump dump2 = new ThreadDump(Instant.now(), "d2",
            List.of(thread2), null, null, null);

        ThreadDumpSnapshot s1 = new ThreadDumpSnapshot(dump1, "", null, null);
        ThreadDumpSnapshot s2 = new ThreadDumpSnapshot(dump2, "", null, null);
        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(List.of(s1, s2)), Map.of());

        String output = result.output();
        assertTrue(output.contains("1.50s"), "Large CPU time should be in seconds format, got: " + output);
    }

    @Test
    void testColorOnStructuredOutput_stateColor() {
        ThreadsAnalyzer analyzer = new ThreadsAnalyzer();

        ThreadInfo runnableThread = new ThreadInfo(
            "running", 1L, null, 5, false, Thread.State.RUNNABLE,
            1.0, 10.0,
            List.of(new StackFrame("com.example.App", "run", "App.java", 10)),
            List.of(), null, null
        );
        ThreadInfo blockedThread = new ThreadInfo(
            "blocked", 2L, null, 5, false, Thread.State.BLOCKED,
            0.5, 10.0,
            List.of(new StackFrame("com.example.App", "lock", "App.java", 20)),
            List.of(), null, null
        );
        ThreadInfo runnableThread2 = new ThreadInfo(
            "running", 1L, null, 5, false, Thread.State.RUNNABLE,
            2.0, 20.0,
            List.of(new StackFrame("com.example.App", "run", "App.java", 10)),
            List.of(), null, null
        );
        ThreadInfo blockedThread2 = new ThreadInfo(
            "blocked", 2L, null, 5, false, Thread.State.BLOCKED,
            0.7, 20.0,
            List.of(new StackFrame("com.example.App", "lock", "App.java", 20)),
            List.of(), null, null
        );

        ThreadDump dump1 = new ThreadDump(Instant.now().minusSeconds(10), "d1",
            List.of(runnableThread, blockedThread), null, null, null);
        ThreadDump dump2 = new ThreadDump(Instant.now(), "d2",
            List.of(runnableThread2, blockedThread2), null, null, null);

        ThreadDumpSnapshot s1 = new ThreadDumpSnapshot(dump1, "", null, null);
        ThreadDumpSnapshot s2 = new ThreadDumpSnapshot(dump2, "", null, null);
        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(List.of(s1, s2)), Map.of());

        assertInstanceOf(AnalyzerOutput.TableOutput.class, result.structured());
        AnalyzerOutput.TableOutput tableOutput = (AnalyzerOutput.TableOutput) result.structured();
        TableModel table = tableOutput.table();

        for (Cell[] row : table.getRows()) {
            Cell stateCell = row[3];
            String threadName = row[0].display();
            if (threadName.equals("running")) {
                assertEquals(Cell.Color.GREEN, stateCell.color(),
                    "RUNNABLE thread should have GREEN state color");
            } else if (threadName.equals("blocked")) {
                assertEquals(Cell.Color.RED, stateCell.color(),
                    "BLOCKED thread should have RED state color");
            }
        }
    }

    @Test
    void testColorOnStructuredOutput_cpuPercentageColor() {
        ThreadsAnalyzer analyzer = new ThreadsAnalyzer();

        ThreadInfo heavyThread = new ThreadInfo(
            "heavy", 1L, null, 5, false, Thread.State.RUNNABLE,
            1.0, 10.0,
            List.of(new StackFrame("com.example.App", "heavy", "App.java", 10)),
            List.of(), null, null
        );
        ThreadInfo lightThread = new ThreadInfo(
            "light", 2L, null, 5, false, Thread.State.RUNNABLE,
            0.1, 10.0,
            List.of(new StackFrame("com.example.App", "light", "App.java", 20)),
            List.of(), null, null
        );
        ThreadInfo heavyThread2 = new ThreadInfo(
            "heavy", 1L, null, 5, false, Thread.State.RUNNABLE,
            10.0, 20.0,
            List.of(new StackFrame("com.example.App", "heavy", "App.java", 10)),
            List.of(), null, null
        );
        ThreadInfo lightThread2 = new ThreadInfo(
            "light", 2L, null, 5, false, Thread.State.RUNNABLE,
            0.2, 20.0,
            List.of(new StackFrame("com.example.App", "light", "App.java", 20)),
            List.of(), null, null
        );

        ThreadDump dump1 = new ThreadDump(Instant.now().minusSeconds(10), "d1",
            List.of(heavyThread, lightThread), null, null, null);
        ThreadDump dump2 = new ThreadDump(Instant.now(), "d2",
            List.of(heavyThread2, lightThread2), null, null, null);

        ThreadDumpSnapshot s1 = new ThreadDumpSnapshot(dump1, "", null, null);
        ThreadDumpSnapshot s2 = new ThreadDumpSnapshot(dump2, "", null, null);
        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(List.of(s1, s2)), Map.of());

        assertInstanceOf(AnalyzerOutput.TableOutput.class, result.structured());
        AnalyzerOutput.TableOutput tableOutput = (AnalyzerOutput.TableOutput) result.structured();
        TableModel table = tableOutput.table();

        for (Cell[] row : table.getRows()) {
            String threadName = row[0].display();
            Cell cpuCell = row[2];
            if (threadName.equals("heavy")) {
                assertEquals(Cell.Color.RED, cpuCell.color(),
                    "High CPU% thread should have RED color, got " + cpuCell.display());
            } else if (threadName.equals("light")) {
                assertNull(cpuCell.color(),
                    "Low CPU% thread should have no color, got " + cpuCell.display());
            }
        }
    }

    @Test
    void testZeroCpuTimeFormatting() {
        ThreadsAnalyzer analyzer = new ThreadsAnalyzer();

        ThreadInfo thread = new ThreadInfo(
            "idle-thread", 1L, null, 5, false, Thread.State.WAITING,
            5.0, 10.0,
            List.of(new StackFrame("java.lang.Object", "wait", "Object.java", 300)),
            List.of(), null, null
        );

        ThreadDump dump = new ThreadDump(Instant.now(), "d1",
            List.of(thread), null, null, null);

        ThreadDumpSnapshot s = new ThreadDumpSnapshot(dump, "", null, null);
        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(List.of(s)), Map.of());

        String output = result.output();
        assertTrue(output.contains("0ms"), "Zero CPU time should show as 0ms, got: " + output);
    }
}
