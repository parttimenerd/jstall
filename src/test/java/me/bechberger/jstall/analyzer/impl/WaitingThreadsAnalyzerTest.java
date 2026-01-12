package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.parser.ThreadDumpParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WaitingThreadsAnalyzerTest {

    @Test
    void testName() {
        WaitingThreadsAnalyzer analyzer = new WaitingThreadsAnalyzer();
        assertEquals("waiting-threads", analyzer.name());
    }

    @Test
    void testSupportedOptions() {
        WaitingThreadsAnalyzer analyzer = new WaitingThreadsAnalyzer();
        Set<String> supported = analyzer.supportedOptions();

        assertTrue(supported.contains("dumps"));
        assertTrue(supported.contains("interval"));
        assertTrue(supported.contains("keep"));
        assertTrue(supported.contains("no-native"));
        assertTrue(supported.contains("stack-depth"));
        assertEquals(5, supported.size());
    }

    @Test
    void testDumpRequirement() {
        WaitingThreadsAnalyzer analyzer = new WaitingThreadsAnalyzer();
        assertEquals(DumpRequirement.MANY, analyzer.dumpRequirement());
    }

    @Test
    void testAnalyzeWithNoDumps() {
        WaitingThreadsAnalyzer analyzer = new WaitingThreadsAnalyzer();
        AnalyzerResult result = analyzer.analyzeThreadDumps(List.of(), Map.of());

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("No thread dumps"));
        assertTrue(result.shouldDisplay());
    }

    /**
     * Test scenario: Two threads waiting on a lock that's always locked.
     * This simulates potential thread starvation.
     */
    @Test
    void testTwoThreadsWaitingOnLockedMonitor() throws IOException {
        WaitingThreadsAnalyzer analyzer = new WaitingThreadsAnalyzer();

        // Create multiple thread dumps showing the same pattern
        List<ThreadDump> dumps = createWaitingThreadDumps(3);

        AnalyzerResult result = analyzer.analyzeThreadDumps(dumps, Map.of());

        // Should detect the waiting threads
        assertEquals(0, result.exitCode());
        assertTrue(result.shouldDisplay(), "Should display results");
        String output = result.output();

        // Verify the output contains information about waiting threads
        assertTrue(output.contains("Threads waiting on the same lock instance") || output.contains("waiting"),
            "Should have header about waiting threads");
        assertTrue(output.contains("WaitingWorker-1") || output.contains("WaitingWorker"),
            "Should identify waiting worker threads");
        assertTrue(output.contains("Lock instance") || output.contains("lock"),
            "Should show lock contention or lock information");
        assertTrue(output.contains("Object.wait") || output.contains("Waiting at"),
            "Should show where threads are waiting");

        System.out.println("Waiting threads analysis:");
        System.out.println(output);
    }

    /**
     * Test scenario: Threads with active work should not be flagged as waiting.
     */
    @Test
    void testActiveThreadsNotFlagged() throws IOException {
        WaitingThreadsAnalyzer analyzer = new WaitingThreadsAnalyzer();

        // Create dumps with active threads
        List<ThreadDump> dumps = createActiveThreadDumps(3);

        AnalyzerResult result = analyzer.analyzeThreadDumps(dumps, Map.of());

        // Should not display anything since no threads are waiting without progress
        assertEquals(0, result.exitCode());
        assertFalse(result.shouldDisplay(), "Active threads should not be flagged");
    }

    /**
     * Creates multiple thread dumps with threads waiting on a locked monitor.
     * Simulates the scenario where two threads are perpetually waiting.
     */
    private List<ThreadDump> createWaitingThreadDumps(int count) throws IOException {
        List<ThreadDump> dumps = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            long timestamp = 1735477200000L + (i * 5000); // 5 second intervals

            // Create a thread dump with:
            // 1. A holder thread that holds the lock (RUNNABLE or TIMED_WAITING)
            // 2. Two worker threads perpetually waiting on the same lock
            String dumpContent = String.format("""
                %d
                Full thread dump Java HotSpot(TM) 64-Bit Server VM (21+35-2513 mixed mode):
                
                "LockHolder" #10 prio=5 os_prio=0 cpu=0.05ms elapsed=%.2fs tid=0x00007f8b0c00b800 nid=0xa runnable [0x00007f8b14e5d000]
                   java.lang.Thread.State: TIMED_WAITING (sleeping)
                	at java.lang.Thread.sleep(java.base@21/Native Method)
                	at com.example.LockHolder.holdLock(LockHolder.java:15)
                	at com.example.LockHolder.run(LockHolder.java:10)
                
                "WaitingWorker-1" #11 prio=5 os_prio=0 cpu=0.00ms elapsed=%.2fs tid=0x00007f8b0c00c000 nid=0xb waiting on condition [0x00007f8b14d5c000]
                   java.lang.Thread.State: WAITING (on object monitor)
                	at java.lang.Object.wait(java.base@21/Native Method)
                	- waiting on <0x00000000d5f78a10> (a java.lang.Object)
                	at java.lang.Object.wait(java.base@21/Object.java:366)
                	at com.example.Worker.doWork(Worker.java:25)
                	- locked <0x00000000d5f78a10> (a java.lang.Object)
                	at com.example.Worker.run(Worker.java:15)
                
                "WaitingWorker-2" #12 prio=5 os_prio=0 cpu=0.00ms elapsed=%.2fs tid=0x00007f8b0c00c800 nid=0xc waiting on condition [0x00007f8b14c5b000]
                   java.lang.Thread.State: WAITING (on object monitor)
                	at java.lang.Object.wait(java.base@21/Native Method)
                	- waiting on <0x00000000d5f78a10> (a java.lang.Object)
                	at java.lang.Object.wait(java.base@21/Object.java:366)
                	at com.example.Worker.doWork(Worker.java:25)
                	- locked <0x00000000d5f78a10> (a java.lang.Object)
                	at com.example.Worker.run(Worker.java:15)
                
                "main" #1 prio=5 os_prio=0 cpu=5.50ms elapsed=%.2fs tid=0x00007f8b0c001000 nid=0x1 runnable [0x00007f8b15e5d000]
                   java.lang.Thread.State: RUNNABLE
                	at com.example.Main.main(Main.java:20)
                """,
                timestamp,
                10.0 + i * 5.0,  // LockHolder elapsed time
                10.0 + i * 5.0,  // WaitingWorker-1 elapsed time
                10.0 + i * 5.0,  // WaitingWorker-2 elapsed time
                10.0 + i * 5.0   // main elapsed time
            );

            dumps.add(ThreadDumpParser.parse(dumpContent));
        }

        return dumps;
    }

    /**
     * Creates thread dumps with active threads (should not be flagged as waiting).
     */
    private List<ThreadDump> createActiveThreadDumps(int count) throws IOException {
        List<ThreadDump> dumps = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            long timestamp = 1735477200000L + (i * 5000);

            String dumpContent = String.format("""
                %d
                Full thread dump Java HotSpot(TM) 64-Bit Server VM (21+35-2513 mixed mode):
                
                "ActiveWorker-1" #10 prio=5 os_prio=0 cpu=%.2fms elapsed=%.2fs tid=0x00007f8b0c00b800 nid=0xa runnable [0x00007f8b14e5d000]
                   java.lang.Thread.State: RUNNABLE
                	at com.example.Worker.compute(Worker.java:30)
                	at com.example.Worker.run(Worker.java:15)
                
                "ActiveWorker-2" #11 prio=5 os_prio=0 cpu=%.2fms elapsed=%.2fs tid=0x00007f8b0c00c000 nid=0xb runnable [0x00007f8b14d5c000]
                   java.lang.Thread.State: RUNNABLE
                	at com.example.Worker.compute(Worker.java:35)
                	at com.example.Worker.run(Worker.java:15)
                
                "main" #1 prio=5 os_prio=0 cpu=50.00ms elapsed=%.2fs tid=0x00007f8b0c001000 nid=0x1 runnable [0x00007f8b15e5d000]
                   java.lang.Thread.State: RUNNABLE
                	at com.example.Main.main(Main.java:20)
                """,
                timestamp,
                100.0 + i * 50.0,  // ActiveWorker-1 increasing CPU time
                10.0 + i * 5.0,
                150.0 + i * 75.0,  // ActiveWorker-2 increasing CPU time
                10.0 + i * 5.0,
                10.0 + i * 5.0
            );

            dumps.add(ThreadDumpParser.parse(dumpContent));
        }

        return dumps;
    }
}