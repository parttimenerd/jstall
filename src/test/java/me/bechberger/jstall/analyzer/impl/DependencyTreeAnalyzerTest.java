package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
import me.bechberger.jthreaddump.model.LockInfo;
import me.bechberger.jthreaddump.model.StackFrame;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.model.ThreadInfo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DependencyTreeAnalyzerTest {

    @Test
    void testName() {
        DependencyTreeAnalyzer analyzer = new DependencyTreeAnalyzer();
        assertEquals("dependency-tree", analyzer.name());
    }

    @Test
    void testSupportedOptions() {
        DependencyTreeAnalyzer analyzer = new DependencyTreeAnalyzer();
        Set<String> supported = analyzer.supportedOptions();

        assertTrue(supported.contains("dump-count"));
        assertTrue(supported.contains("interval"));
        assertTrue(supported.contains("keep"));
                assertTrue(supported.contains("graph-format"));
                assertEquals(4, supported.size());
    }

    @Test
    void testDumpRequirement() {
        DependencyTreeAnalyzer analyzer = new DependencyTreeAnalyzer();
        assertEquals(DumpRequirement.ANY, analyzer.dumpRequirement());
    }

    @Test
    void testNoDependencies() {
        DependencyTreeAnalyzer analyzer = new DependencyTreeAnalyzer();

        ThreadInfo thread1 = new ThreadInfo(
                "thread-1",
                1L,
                null,
                5,
                false,
                Thread.State.RUNNABLE,
                1.0,
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
                List.of(thread1),
                null,
                null,
                null
        );

        ThreadDumpSnapshot snapshot = new ThreadDumpSnapshot(dump, "", null, null);
        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(List.of(snapshot)), Map.of());

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("No lock-based dependency trees found"));
    }

    @Test
    void testSimpleDependency() {
        DependencyTreeAnalyzer analyzer = new DependencyTreeAnalyzer();

        // Thread 1 holds lock A
        ThreadInfo thread1 = new ThreadInfo(
                "thread-1",
                1L,
                null,
                5,
                false,
                Thread.State.RUNNABLE,
                1.0,
                10.0,
                List.of(
                        new StackFrame("com.example.MyApp", "method1", "MyApp.java", 100)
                ),
                List.of(
                        new LockInfo("0x12345", "java.lang.Object", LockInfo.LockOperation.LOCKED)
                ),
                null,
                null
        );

        // Thread 2 waits on lock A (held by thread 1)
        ThreadInfo thread2 = new ThreadInfo(
                "thread-2",
                2L,
                null,
                5,
                false,
                Thread.State.BLOCKED,
                0.5,
                10.0,
                List.of(
                        new StackFrame("com.example.MyApp", "method2", "MyApp.java", 200)
                ),
                List.of(
                        new LockInfo("0x12345", "java.lang.Object", LockInfo.LockOperation.WAITING_TO_LOCK)
                ),
                null,
                null
        );

        ThreadDump dump = new ThreadDump(
                Instant.now(),
                "Test Dump",
                List.of(thread1, thread2),
                null,
                null,
                null
        );

        ThreadDumpSnapshot snapshot = new ThreadDumpSnapshot(dump, "", null, null);
        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(List.of(snapshot)), Map.of());

        assertEquals(0, result.exitCode());
        String output = result.output();

        // Should show dependency
        assertTrue(output.contains("Thread Dependency Graph"));
        assertTrue(output.contains("thread-2"));
        assertTrue(output.contains("thread-1"));
        assertTrue(output.contains("0x12345")); // Lock ID in root locks section
        assertTrue(output.contains("1 potential bottleneck(s)"));
        assertTrue(output.contains("1 blocked thread(s)")); // In tree root header
        assertTrue(output.contains("Total blocked threads: 1"));
    }

    @Test
    void testDependencyChain() {
        DependencyTreeAnalyzer analyzer = new DependencyTreeAnalyzer();

        // Thread 1 holds lock A, waits on lock B
        ThreadInfo thread1 = new ThreadInfo(
                "io-thread-1",
                1L,
                null,
                5,
                false,
                Thread.State.BLOCKED,
                1.0,
                10.0,
                List.of(
                        new StackFrame("java.io.FileInputStream", "read", "FileInputStream.java", 100)
                ),
                List.of(
                        new LockInfo("0xAAAA", "java.lang.Object", LockInfo.LockOperation.LOCKED),
                        new LockInfo("0xBBBB", "java.lang.Object", LockInfo.LockOperation.WAITING_TO_LOCK)
                ),
                null,
                null
        );

        // Thread 2 holds lock B, waits on lock C
        ThreadInfo thread2 = new ThreadInfo(
                "network-thread-2",
                2L,
                null,
                5,
                false,
                Thread.State.BLOCKED,
                0.5,
                10.0,
                List.of(
                        new StackFrame("sun.nio.ch.KQueue", "poll", null, null)
                ),
                List.of(
                        new LockInfo("0xBBBB", "java.lang.Object", LockInfo.LockOperation.LOCKED),
                        new LockInfo("0xCCCC", "java.lang.Object", LockInfo.LockOperation.WAITING_TO_LOCK)
                ),
                null,
                null
        );

        // Thread 3 holds lock C (root - owns but doesn't wait)
        ThreadInfo thread3 = new ThreadInfo(
                "compute-thread-3",
                3L,
                null,
                5,
                false,
                Thread.State.RUNNABLE,
                2.0,
                10.0,
                List.of(
                        new StackFrame("com.example.MyApp", "compute", "MyApp.java", 300)
                ),
                List.of(
                        new LockInfo("0xCCCC", "java.lang.Object", LockInfo.LockOperation.LOCKED)
                ),
                null,
                null
        );

        ThreadDump dump = new ThreadDump(
                Instant.now(),
                "Test Dump",
                List.of(thread1, thread2, thread3),
                null,
                null,
                null
        );

        ThreadDumpSnapshot snapshot = new ThreadDumpSnapshot(dump, "", null, null);
        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(List.of(snapshot)), Map.of());

        assertEquals(0, result.exitCode());
        String output = result.output();

        // Should show the tree structure
        assertTrue(output.contains("Thread Dependency Graph"));
        assertTrue(output.contains("Total blocked threads: 2"));

        // Should include category prefixes
        assertTrue(output.contains("[I/O Read]") || output.contains("I/O Read"));
        assertTrue(output.contains("[Network]") || output.contains("Network"));
        assertTrue(output.contains("[Computation]") || output.contains("Computation"));

        // Root should be compute-thread-3 (only thread that owns locks but doesn't wait)
        assertTrue(output.contains("compute-thread-3"));
        assertTrue(output.contains("2 blocked thread(s)"));
    }

    @Test
    void testMultipleDependencies() {
        DependencyTreeAnalyzer analyzer = new DependencyTreeAnalyzer();

        // Thread 1 holds lock A
        ThreadInfo thread1 = new ThreadInfo(
                "owner-thread",
                1L,
                null,
                5,
                false,
                Thread.State.RUNNABLE,
                1.0,
                10.0,
                List.of(
                        new StackFrame("com.example.MyApp", "method1", "MyApp.java", 100)
                ),
                List.of(
                        new LockInfo("0x12345", "java.lang.Object", LockInfo.LockOperation.LOCKED)
                ),
                null,
                null
        );

        // Thread 2 waits on lock A
        ThreadInfo thread2 = new ThreadInfo(
                "waiter-thread-1",
                2L,
                null,
                5,
                false,
                Thread.State.BLOCKED,
                0.5,
                10.0,
                List.of(
                        new StackFrame("com.example.MyApp", "method2", "MyApp.java", 200)
                ),
                List.of(
                        new LockInfo("0x12345", "java.lang.Object", LockInfo.LockOperation.WAITING_TO_LOCK)
                ),
                null,
                null
        );

        // Thread 3 also waits on lock A
        ThreadInfo thread3 = new ThreadInfo(
                "waiter-thread-2",
                3L,
                null,
                5,
                false,
                Thread.State.BLOCKED,
                0.3,
                10.0,
                List.of(
                        new StackFrame("com.example.MyApp", "method3", "MyApp.java", 300)
                ),
                List.of(
                        new LockInfo("0x12345", "java.lang.Object", LockInfo.LockOperation.WAITING_TO_LOCK)
                ),
                null,
                null
        );

        ThreadDump dump = new ThreadDump(
                Instant.now(),
                "Test Dump",
                List.of(thread1, thread2, thread3),
                null,
                null,
                null
        );

        ThreadDumpSnapshot snapshot = new ThreadDumpSnapshot(dump, "", null, null);
        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(List.of(snapshot)), Map.of());

        assertEquals(0, result.exitCode());
        String output = result.output();

        // Should show both waiters blocked by the same owner
        assertTrue(output.contains("Total blocked threads: 2"));
        assertTrue(output.contains("Total dependencies: 2"));
        assertTrue(output.contains("waiter-thread-1"));
        assertTrue(output.contains("waiter-thread-2"));
        assertTrue(output.contains("owner-thread"));
    }

    @Test
    void testMultipleDumpsAggregation() {
        DependencyTreeAnalyzer analyzer = new DependencyTreeAnalyzer();

        Instant baseTime = Instant.parse("2024-01-01T00:00:00Z");

        // First dump: owner holds lock, waiter blocked
        ThreadInfo owner1 = new ThreadInfo(
                "owner-thread",
                1L,
                null,
                5,
                false,
                Thread.State.RUNNABLE,
                1.0,
                10.0,
                List.of(
                        new StackFrame("com.example.MyApp", "method1", "MyApp.java", 100)
                ),
                List.of(
                        new LockInfo("0xABC", "java.lang.Object", LockInfo.LockOperation.LOCKED)
                ),
                null,
                null
        );

        ThreadInfo waiter1 = new ThreadInfo(
                "waiter-thread",
                2L,
                null,
                5,
                false,
                Thread.State.BLOCKED,
                0.5,
                10.0,
                List.of(
                        new StackFrame("com.example.MyApp", "method2", "MyApp.java", 200)
                ),
                List.of(
                        new LockInfo("0xABC", "java.lang.Object", LockInfo.LockOperation.WAITING_TO_LOCK)
                ),
                null,
                null
        );

        ThreadDump dump1 = new ThreadDump(
                baseTime,
                "Dump 1",
                List.of(owner1, waiter1),
                null,
                null,
                null
        );

        // Second dump (5 seconds later): same pattern persists
        ThreadInfo owner2 = new ThreadInfo(
                "owner-thread",
                1L,
                null,
                5,
                false,
                Thread.State.RUNNABLE,
                2.0,
                15.0,
                List.of(
                        new StackFrame("com.example.MyApp", "method1", "MyApp.java", 100)
                ),
                List.of(
                        new LockInfo("0xABC", "java.lang.Object", LockInfo.LockOperation.LOCKED)
                ),
                null,
                null
        );

        ThreadInfo waiter2 = new ThreadInfo(
                "waiter-thread",
                2L,
                null,
                5,
                false,
                Thread.State.BLOCKED,
                1.0,
                15.0,
                List.of(
                        new StackFrame("com.example.MyApp", "method2", "MyApp.java", 200)
                ),
                List.of(
                        new LockInfo("0xABC", "java.lang.Object", LockInfo.LockOperation.WAITING_TO_LOCK)
                ),
                null,
                null
        );

        ThreadDump dump2 = new ThreadDump(
                baseTime.plusSeconds(5),
                "Dump 2",
                List.of(owner2, waiter2),
                null,
                null,
                null
        );

        ThreadDumpSnapshot snapshot1 = new ThreadDumpSnapshot(dump1, "", null, null);
        ThreadDumpSnapshot snapshot2 = new ThreadDumpSnapshot(dump2, "", null, null);
        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(List.of(snapshot1, snapshot2)), Map.of());

        assertEquals(0, result.exitCode());
        String output = result.output();

        // Should aggregate: same root across 2 dumps
        assertTrue(output.contains("owner-thread"), "Should contain root thread");
        assertTrue(output.contains("waiter-thread"), "Should contain blocked thread");
        assertTrue(output.contains("2 dump(s)"), "Should show persistence across 2 dumps");
        assertTrue(output.contains("[+5s]"), "Should show time diff of 5 seconds for second dump");
        assertTrue(output.contains("Bottleneck roots: 1"), "Should have 1 bottleneck root");
    }

    @Test
    void testActivityCategorization() {
        DependencyTreeAnalyzer analyzer = new DependencyTreeAnalyzer();

        // DB thread holds lock
        ThreadInfo dbThread = new ThreadInfo(
                "db-thread",
                1L,
                null,
                5,
                false,
                Thread.State.RUNNABLE,
                5.0,
                10.0,
                List.of(
                        new StackFrame("java.sql.Connection", "executeQuery", "Connection.java", 100)
                ),
                List.of(
                        new LockInfo("0xDB01", "java.lang.Object", LockInfo.LockOperation.LOCKED)
                ),
                null,
                null
        );

        // I/O thread waits on DB thread's lock
        ThreadInfo ioThread = new ThreadInfo(
                "io-thread",
                2L,
                null,
                5,
                false,
                Thread.State.BLOCKED,
                0.5,
                10.0,
                List.of(
                        new StackFrame("java.io.FileOutputStream", "write", "FileOutputStream.java", 200)
                ),
                List.of(
                        new LockInfo("0xDB01", "java.lang.Object", LockInfo.LockOperation.WAITING_TO_LOCK)
                ),
                null,
                null
        );

        ThreadDump dump = new ThreadDump(
                Instant.now(),
                "Test Dump",
                List.of(dbThread, ioThread),
                null,
                null,
                null
        );

        ThreadDumpSnapshot snapshot = new ThreadDumpSnapshot(dump, "", null, null);
        AnalyzerResult result = analyzer.analyze(ResolvedData.fromDumps(List.of(snapshot)), Map.of());

        assertEquals(0, result.exitCode());
        String output = result.output();

        // Check for expected output structure
        assertTrue(output.contains("Thread Dependency Graph"), "Should contain header");
        assertTrue(output.contains("io-thread"), "Should contain blocked thread name");
        assertTrue(output.contains("db-thread"), "Should contain root thread name");
        assertTrue(output.contains("0xDB01"), "Should contain lock ID");

        // Check for category prefixes
        assertTrue(output.contains("[Database]"), "Should contain Database category");
        assertTrue(output.contains("[I/O Write]"), "Should contain I/O Write category");

        // Check for CPU times
        assertTrue(output.contains("CPU: 0.50s"), "Should show blocked thread CPU time");
        assertTrue(output.contains("CPU: 5.00s"), "Should show root thread CPU time");

        // Check for summary
        assertTrue(output.contains("Summary:"), "Should contain summary section");
        assertTrue(output.contains("Total blocked threads: 1"), "Should show blocked thread count");
        assertTrue(output.contains("Total dependencies: 1"), "Should show dependency count");

        // Verify the categorized thread names in output
        assertTrue(output.contains("[I/O Write] io-thread"), "Should show categorized blocked thread");
        assertTrue(output.contains("[Database] db-thread"), "Should show categorized root thread");
    }

    @Test
    void testDisappearingNodes() {
        DependencyTreeAnalyzer analyzer = new DependencyTreeAnalyzer();

        Instant baseTime = Instant.parse("2024-01-01T00:00:00Z");

        // --- Dump 1: owner blocks waiter-A and waiter-B ---
        ThreadInfo owner1 = new ThreadInfo(
                "owner-thread", 1L, null, 5, false, Thread.State.RUNNABLE, 1.0, 10.0,
                List.of(new StackFrame("com.example.MyApp", "method1", "MyApp.java", 100)),
                List.of(new LockInfo("0xABC", "java.lang.Object", LockInfo.LockOperation.LOCKED)),
                null, null
        );
        ThreadInfo waiterA1 = new ThreadInfo(
                "waiter-A", 2L, null, 5, false, Thread.State.BLOCKED, 0.5, 10.0,
                List.of(new StackFrame("com.example.MyApp", "methodA", "MyApp.java", 200)),
                List.of(new LockInfo("0xABC", "java.lang.Object", LockInfo.LockOperation.WAITING_TO_LOCK)),
                null, null
        );
        ThreadInfo waiterB1 = new ThreadInfo(
                "waiter-B", 3L, null, 5, false, Thread.State.BLOCKED, 0.3, 10.0,
                List.of(new StackFrame("com.example.MyApp", "methodB", "MyApp.java", 300)),
                List.of(new LockInfo("0xABC", "java.lang.Object", LockInfo.LockOperation.WAITING_TO_LOCK)),
                null, null
        );
        ThreadDump dump1 = new ThreadDump(
                baseTime, "Dump 1", List.of(owner1, waiterA1, waiterB1), null, null, null
        );

        // --- Dump 2: waiter-B disappeared (got unblocked), only waiter-A remains ---
        ThreadInfo owner2 = new ThreadInfo(
                "owner-thread", 1L, null, 5, false, Thread.State.RUNNABLE, 2.0, 15.0,
                List.of(new StackFrame("com.example.MyApp", "method1", "MyApp.java", 100)),
                List.of(new LockInfo("0xABC", "java.lang.Object", LockInfo.LockOperation.LOCKED)),
                null, null
        );
        ThreadInfo waiterA2 = new ThreadInfo(
                "waiter-A", 2L, null, 5, false, Thread.State.BLOCKED, 1.0, 15.0,
                List.of(new StackFrame("com.example.MyApp", "methodA", "MyApp.java", 200)),
                List.of(new LockInfo("0xABC", "java.lang.Object", LockInfo.LockOperation.WAITING_TO_LOCK)),
                null, null
        );
        ThreadDump dump2 = new ThreadDump(
                baseTime.plusSeconds(5), "Dump 2", List.of(owner2, waiterA2), null, null, null
        );

        ThreadDumpSnapshot snapshot1 = new ThreadDumpSnapshot(dump1, "", null, null);
        ThreadDumpSnapshot snapshot2 = new ThreadDumpSnapshot(dump2, "", null, null);
        AnalyzerResult result = analyzer.analyze(
                ResolvedData.fromDumps(List.of(snapshot1, snapshot2)), Map.of());

        assertEquals(0, result.exitCode());
        String output = result.output();

        // waiter-B disappeared from dump2 but should still appear in the merged tree
        assertTrue(output.contains("waiter-B"), "Disappeared thread should still appear in tree");
        assertTrue(output.contains("[disappeared]"), "Disappeared thread should be annotated");
        // waiter-A persists and should NOT be marked as disappeared
        assertTrue(output.contains("waiter-A"), "Persistent thread should appear");
        // The merged tree should count both blocked threads
        assertTrue(output.contains("Total blocked threads: 2"),
                "Should count both persistent and disappeared threads");
    }

    @Test
    void testMultipleBlockingLocksPicksTopLock() {
        DependencyTreeAnalyzer analyzer = new DependencyTreeAnalyzer();

        ThreadInfo thread = new ThreadInfo(
                "multi-blocking",
                1L,
                null,
                5,
                false,
                Thread.State.BLOCKED,
                0.0,
                0.0,
                List.of(new StackFrame("com.example.MyApp", "method", "MyApp.java", 1)),
                List.of(
                        // Lower priority
                        new LockInfo("0xB", "java.lang.Object", LockInfo.LockOperation.PARKING),
                        // Top priority
                        new LockInfo("0xA", "java.lang.Object", LockInfo.LockOperation.WAITING_TO_LOCK)
                ),
                null,
                null
        );

        LockInfo picked = analyzer.getWaitedOnLock(thread).orElseThrow();
        assertEquals("0xA", picked.lockId());
        assertEquals(LockInfo.LockOperation.WAITING_TO_LOCK, picked.operation());
    }
}