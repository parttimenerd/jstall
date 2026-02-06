package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
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

class DependencyGraphAnalyzerTest {

    @Test
    void testName() {
        DependencyGraphAnalyzer analyzer = new DependencyGraphAnalyzer();
        assertEquals("dependency-graph", analyzer.name());
    }

    @Test
    void testSupportedOptions() {
        DependencyGraphAnalyzer analyzer = new DependencyGraphAnalyzer();
        Set<String> supported = analyzer.supportedOptions();

        assertTrue(supported.contains("dumps"));
        assertTrue(supported.contains("interval"));
        assertTrue(supported.contains("keep"));
        assertEquals(3, supported.size());
    }

    @Test
    void testDumpRequirement() {
        DependencyGraphAnalyzer analyzer = new DependencyGraphAnalyzer();
        assertEquals(DumpRequirement.ANY, analyzer.dumpRequirement());
    }

    @Test
    void testNoDependencies() {
        DependencyGraphAnalyzer analyzer = new DependencyGraphAnalyzer();

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

        AnalyzerResult result = analyzer.analyzeThreadDumps(List.of(dump), Map.of());

        assertEquals(0, result.exitCode());
        assertTrue(result.output().isEmpty());
    }

    @Test
    void testSimpleDependency() {
        DependencyGraphAnalyzer analyzer = new DependencyGraphAnalyzer();

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

        AnalyzerResult result = analyzer.analyzeThreadDumps(List.of(dump), Map.of());

        assertEquals(0, result.exitCode());
        String output = result.output();

        // Should show dependency
        assertTrue(output.contains("Thread Dependency Graph"));
        assertTrue(output.contains("thread-2"));
        assertTrue(output.contains("thread-1"));
        assertTrue(output.contains("→")); // Arrow showing dependency
        assertTrue(output.contains("0x12345")); // Lock ID
        assertTrue(output.contains("Total waiting threads: 1"));
    }

    @Test
    void testDependencyChain() {
        DependencyGraphAnalyzer analyzer = new DependencyGraphAnalyzer();

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

        // Thread 3 holds lock C
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

        AnalyzerResult result = analyzer.analyzeThreadDumps(List.of(dump), Map.of());

        assertEquals(0, result.exitCode());
        String output = result.output();

        // Should show dependencies and chain
        assertTrue(output.contains("Thread Dependency Graph"));
        assertTrue(output.contains("Total waiting threads: 2"));
        assertTrue(output.contains("Dependency Chains Detected"));
        assertTrue(output.contains("Chain:"));

        // Should include category prefixes
        assertTrue(output.contains("[I/O Read]") || output.contains("I/O Read"));
        assertTrue(output.contains("[Network]") || output.contains("Network"));
        assertTrue(output.contains("[Computation]") || output.contains("Computation"));
    }

    @Test
    void testMultipleDependencies() {
        DependencyGraphAnalyzer analyzer = new DependencyGraphAnalyzer();

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

        AnalyzerResult result = analyzer.analyzeThreadDumps(List.of(dump), Map.of());

        assertEquals(0, result.exitCode());
        String output = result.output();

        // Should show both waiters depending on the same owner
        assertTrue(output.contains("Total waiting threads: 2"));
        assertTrue(output.contains("Total dependencies: 2"));
        assertTrue(output.contains("waiter-thread-1"));
        assertTrue(output.contains("waiter-thread-2"));
        assertTrue(output.contains("owner-thread"));
    }

    @Test
    void testMultipleDumpsUsesLatest() {
        DependencyGraphAnalyzer analyzer = new DependencyGraphAnalyzer();

        // First dump: thread-1 holds lock, thread-2 waits on it
        ThreadInfo oldThread1 = new ThreadInfo(
                "old-owner",
                1L,
                null,
                5,
                false,
                Thread.State.RUNNABLE,
                1.0,
                10.0,
                List.of(
                        new StackFrame("com.example.OldApp", "oldMethod", "OldApp.java", 100)
                ),
                List.of(
                        new LockInfo("0xOLD1", "java.lang.Object", LockInfo.LockOperation.LOCKED)
                ),
                null,
                null
        );

        ThreadInfo oldThread2 = new ThreadInfo(
                "old-waiter",
                2L,
                null,
                5,
                false,
                Thread.State.BLOCKED,
                0.5,
                10.0,
                List.of(
                        new StackFrame("com.example.OldApp", "oldMethod2", "OldApp.java", 200)
                ),
                List.of(
                        new LockInfo("0xOLD1", "java.lang.Object", LockInfo.LockOperation.WAITING_TO_LOCK)
                ),
                null,
                null
        );

        ThreadDump oldDump = new ThreadDump(
                Instant.now().minusSeconds(10),
                "Old Dump",
                List.of(oldThread1, oldThread2),
                null,
                null,
                null
        );

        // Second dump (latest): different threads with different locks
        ThreadInfo newThread1 = new ThreadInfo(
                "new-owner",
                3L,
                null,
                5,
                false,
                Thread.State.RUNNABLE,
                2.0,
                10.0,
                List.of(
                        new StackFrame("java.sql.Connection", "executeQuery", "Connection.java", 100)
                ),
                List.of(
                        new LockInfo("0xNEW1", "java.lang.Object", LockInfo.LockOperation.LOCKED)
                ),
                null,
                null
        );

        ThreadInfo newThread2 = new ThreadInfo(
                "new-waiter",
                4L,
                null,
                5,
                false,
                Thread.State.BLOCKED,
                1.0,
                10.0,
                List.of(
                        new StackFrame("java.io.FileOutputStream", "write", "FileOutputStream.java", 200)
                ),
                List.of(
                        new LockInfo("0xNEW1", "java.lang.Object", LockInfo.LockOperation.WAITING_TO_LOCK)
                ),
                null,
                null
        );

        ThreadDump newDump = new ThreadDump(
                Instant.now(),
                "New Dump",
                List.of(newThread1, newThread2),
                null,
                null,
                null
        );

        // Analyze with both dumps
        AnalyzerResult result = analyzer.analyzeThreadDumps(List.of(oldDump, newDump), Map.of());

        assertEquals(0, result.exitCode());
        String output = result.output();

        // Should only show threads from the NEW dump
        assertTrue(output.contains("new-waiter"), "Should contain new waiter thread");
        assertTrue(output.contains("new-owner"), "Should contain new owner thread");
        assertTrue(output.contains("0xNEW1"), "Should contain new lock ID");

        // Should NOT show threads from the OLD dump
        assertFalse(output.contains("old-waiter"), "Should NOT contain old waiter thread");
        assertFalse(output.contains("old-owner"), "Should NOT contain old owner thread");
        assertFalse(output.contains("0xOLD1"), "Should NOT contain old lock ID");

        // Should show correct categories from new dump
        assertTrue(output.contains("[Database]"), "Should categorize database thread");
        assertTrue(output.contains("[I/O Write]"), "Should categorize I/O write thread");

        // Should show correct summary
        assertTrue(output.contains("Total waiting threads: 1"), "Should show 1 waiting thread from latest dump");
    }

    @Test
    void testActivityCategorization() {
        DependencyGraphAnalyzer analyzer = new DependencyGraphAnalyzer();

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

        AnalyzerResult result = analyzer.analyzeThreadDumps(List.of(dump), Map.of());

        assertEquals(0, result.exitCode());
        String output = result.output();

        // Check for expected output structure
        assertTrue(output.contains("Thread Dependency Graph"), "Should contain header");
        assertTrue(output.contains("io-thread"), "Should contain waiting thread name");
        assertTrue(output.contains("db-thread"), "Should contain owner thread name");
        assertTrue(output.contains("→"), "Should contain dependency arrow");
        assertTrue(output.contains("0xDB01"), "Should contain lock ID");

        // Check for category prefixes
        assertTrue(output.contains("[Database]"), "Should contain Database category");
        assertTrue(output.contains("[I/O Write]"), "Should contain I/O Write category");

        // Check for thread states
        assertTrue(output.contains("Waiter state: BLOCKED"), "Should show waiter state");
        assertTrue(output.contains("Owner state:  RUNNABLE"), "Should show owner state");

        // Check for CPU times
        assertTrue(output.contains("CPU: 0.50s"), "Should show waiter CPU time");
        assertTrue(output.contains("CPU: 5.00s"), "Should show owner CPU time");

        // Check for summary
        assertTrue(output.contains("Summary:"), "Should contain summary section");
        assertTrue(output.contains("Total waiting threads: 1"), "Should show waiting thread count");
        assertTrue(output.contains("Total dependencies: 1"), "Should show dependency count");

        // Verify the full dependency line format
        assertTrue(output.contains("[I/O Write] io-thread"), "Should show categorized waiter");
        assertTrue(output.contains("[Database] db-thread"), "Should show categorized owner");
    }

    @Test
    void testMultipleBlockingLocksPicksTopLock() {
        DependencyGraphAnalyzer analyzer = new DependencyGraphAnalyzer();

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