package me.bechberger.jstall.util.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextCompressorTest {

    private static final String CLASSLOADER_SECTION =
        "=== vm-classloader-stats ===\n" +
        "VM.classloader_stats (2 samples):\n" +
        "Type                                                     Classes\n" +
        "-------------------------------------------------------  -------\n" +
        "org.gradle.internal.classloader.VisitableURLClassLoader    9,731\n" +
        "<boot class loader>                                        3,826\n" +
        "jdk.internal.loader.ClassLoaders$AppClassLoader            1,680\n" +
        "Total                                                     18,778\n";

    private static final String METASPACE_SECTION =
        "=== vm-metaspace ===\n" +
        "=== Usage ===\n" +
        "VM.metaspace (2 samples):\n" +
        "Type       Chunks  Used\n" +
        "---------  ------  ----\n" +
        "Non-Class    4714  84 MB\n" +
        "Class        1764  12 MB\n" +
        "Both         6478  96 MB\n" +
        "=== Virtual Space ===\n" +
        "Space             Reserved  Committed\n" +
        "---------------  ---------  ---------\n" +
        "Non-class space  128.00 MB   85.12 MB\n" +
        "Class space      320.00 MB   13.06 MB\n" +
        "Both             448.00 MB   98.19 MB\n" +
        "=== Summary ===\n" +
        "Waste: 5.21 KB\n" +
        "MaxMetaspaceSize: 384.00 MB  CDS: off\n";

    private static final String VITALS_UNAVAILABLE =
        "=== vm-vitals ===\n" +
        "VM.vitals not available (requires SapMachine JVM)\n";

    private static final String COMPILER_IDLE =
        "=== compiler-queue ===\n" +
        "Compiler queue trend (3 samples):\n" +
        "\n" +
        "Summary:\n" +
        "  Active compilations: 0 (range: 0-0)\n" +
        "  Queued tasks: 0 (range: 0-0)\n" +
        "\n" +
        "Per-sample breakdown:\n" +
        "Time      Active  Queued\n" +
        "--------  ------  ------\n" +
        "06:43:55       0       0\n" +
        "06:44:00       0       0\n" +
        "06:44:05       0       0\n";

    @Test
    void classloaderStatsSuppressedWhenFewLoaders() {
        // ≤5 class loaders — section suppressed entirely
        String result = ContextCompressor.compress(CLASSLOADER_SECTION);
        assertFalse(result.contains("vm-classloader-stats"), "section suppressed for ≤5 loaders");
        assertFalse(result.contains("VisitableURLClassLoader"), "individual loader rows not present");
    }

    @Test
    void metaspaceReducedToSummaryAndBothRow() {
        String result = ContextCompressor.compress(METASPACE_SECTION);
        assertTrue(result.contains("vm-metaspace"), "section header preserved");
        assertTrue(result.contains("Both"), "Both row preserved");
        assertFalse(result.contains("Non-class space"), "virtual space table removed");
        assertFalse(result.contains("Non-Class"), "Non-Class usage row removed");
        // MaxMetaspaceSize is inlined into the Both row — check value appears somewhere
        assertTrue(result.contains("384"), "MaxMetaspaceSize value preserved inline");
    }

    @Test
    void vmVitalsUnavailableDropped() {
        String result = ContextCompressor.compress(VITALS_UNAVAILABLE);
        assertFalse(result.contains("vm-vitals"), "unavailable vm-vitals section dropped");
    }

    @Test
    void compilerQueueIdleSuppressed() {
        // Idle compiler queue (0/0) is suppressed entirely — no signal for the model
        String result = ContextCompressor.compress(COMPILER_IDLE);
        assertFalse(result.contains("compiler-queue"), "idle compiler-queue section suppressed");
        assertFalse(result.contains("Per-sample breakdown"), "per-sample table not present");
        assertFalse(result.contains("06:43:55"), "individual sample rows not present");
    }

    @Test
    void nullAndBlankInputReturned() {
        assertNull(ContextCompressor.compress(null));
        assertEquals("", ContextCompressor.compress("  ").strip());
    }

    @Test
    void unknownSectionsPassedThrough() {
        String input = "=== most-work ===\nSome thread info\nmore info\n";
        String result = ContextCompressor.compress(input);
        assertTrue(result.contains("most-work"), "section header preserved");
        assertTrue(result.contains("Some thread info"), "body preserved");
    }

    @Test
    void mostWorkStacksTruncatedToFourFrames() {
        String input =
            "=== most-work ===\n" +
            "1. some-thread\n" +
            "   CPU time: 0.01s\n" +
            "   Common stack prefix:\n" +
            "     at Frame1\n" +
            "     at Frame2\n" +
            "     at Frame3\n" +
            "     at Frame4\n" +
            "     at Frame5\n" +
            "     at Frame6\n";
        String result = ContextCompressor.compress(input);
        assertTrue(result.contains("Frame4"), "first 4 frames kept");
        assertFalse(result.contains("Frame5"), "5th frame truncated");
        assertFalse(result.contains("Frame6"), "6th frame truncated");
    }

    @Test
    void threadCountWarningAppearsWhenHigh() {
        String input =
            "=== threads ===\n" +
            "Threads (2 dumps):\n" +
            "Thread state distribution: 500 TIMED_WAITING, 8 RUNNABLE, 1 WAITING\n" +
            "THREAD  CPU TIME  CPU %  STATES  ACTIVITY  TOP STACK FRAME\n";
        String result = ContextCompressor.compress(input);
        assertTrue(result.contains("thread-count-warning"), "warning section present: " + result);
        assertTrue(result.contains("509"), "total count 509 in warning: " + result);
        assertTrue(result.contains("extremely high"), "severity label present: " + result);
    }

    @Test
    void threadCountWarningAbsentWhenLow() {
        String input =
            "=== threads ===\n" +
            "Threads (2 dumps):\n" +
            "Thread state distribution: 20 TIMED_WAITING, 8 RUNNABLE, 1 WAITING\n" +
            "THREAD  CPU TIME  CPU %  STATES  ACTIVITY  TOP STACK FRAME\n";
        String result = ContextCompressor.compress(input);
        assertFalse(result.contains("thread-count-warning"), "no warning for 29 threads: " + result);
    }

    @Test
    void gcHeapInfoStripsDetailsColumn() {
        String input =
            "=== gc-heap-info ===\n" +
            "GC.heap_info (last dump absolute + change):\n" +
            "Metric                                 Value  Details                       Δ\n" +
            "---------------------  ---------------------  ---------  --------------------\n" +
            "Heap used                   338,324K | 89.3%  330.4 MiB  Δ +1,358K / +1.3 MiB\n" +
            "Heap total                          378,880K  370.0 MiB        Δ +0K / +0 KiB\n" +
            "Class space used                     12,308K  12.0 MiB         Δ +0K / +0 KiB\n";
        String result = ContextCompressor.compress(input);
        assertTrue(result.contains("Heap used"), "heap used row kept");
        assertFalse(result.contains("Heap total"), "heap total row dropped");
        assertFalse(result.contains("Class space"), "class space row dropped");
        // Details column (MiB) stripped
        assertFalse(result.contains("330.4 MiB"), "details column stripped");
    }

    @Test
    void hiddenWaitingThreadsNoteAppearsWhenWaitingNotInTable() {
        String input =
            "=== threads ===\n" +
            "Threads (2 dumps):\n" +
            "Thread state distribution: 9 WAITING, 3 RUNNABLE\n" +
            "THREAD  CPU TIME  CPU %  STATES  ACTIVITY  TOP STACK FRAME\n" +
            "thread-a  0ms  0%  RUNNABLE  Unknown  SomeFrame\n";
        String result = ContextCompressor.compress(input);
        assertTrue(result.contains("hidden-waiting-threads"), "hidden waiting section present: " + result);
        assertTrue(result.contains("9 WAITING"), "count included: " + result);
        assertTrue(result.contains("ReentrantLock"), "cause hint present: " + result);
        assertTrue(result.contains("get_threads_by_state WAITING"), "tool call hint present: " + result);
    }

    @Test
    void hiddenWaitingThreadsNoteAbsentWhenAllWaitingShownInTable() {
        String input =
            "=== threads ===\n" +
            "Threads (2 dumps):\n" +
            "Thread state distribution: 2 WAITING, 3 RUNNABLE\n" +
            "THREAD  CPU TIME  CPU %  STATES  ACTIVITY  TOP STACK FRAME\n" +
            "thread-a  0ms  0%  WAITING  Lock Wait  SomeFrame\n" +
            "thread-b  0ms  0%  WAITING  Native  SomeFrame\n";
        String result = ContextCompressor.compress(input);
        assertFalse(result.contains("hidden-waiting-threads"), "no hidden note when all WAITING shown: " + result);
    }

    @Test
    void hiddenWaitingThreadsNoteAbsentWhenOnlyFewHidden() {
        // 13 WAITING, 12 in table, 1 hidden — below threshold of 3 hidden
        String input =
            "=== threads ===\n" +
            "Threads (2 dumps):\n" +
            "Thread state distribution: 13 WAITING, 3 RUNNABLE\n" +
            "THREAD  CPU TIME  CPU %  STATES  ACTIVITY  TOP STACK FRAME\n" +
            "worker-1  2000ms  8%  WAITING: 50%, RUNNABLE: 50%  Lock Wait: 50%, Computation: 50%  Scenarios3.lambda\n" +
            "worker-2  2000ms  8%  WAITING: 50%, RUNNABLE: 50%  Lock Wait: 50%, Computation: 50%  Scenarios3.lambda\n" +
            "worker-3  2000ms  8%  WAITING: 50%, RUNNABLE: 50%  Lock Wait: 50%, Computation: 50%  Scenarios3.lambda\n" +
            "worker-4  2000ms  8%  WAITING: 50%, RUNNABLE: 50%  Lock Wait: 50%, Computation: 50%  Scenarios3.lambda\n" +
            "worker-5  2000ms  8%  WAITING: 50%, RUNNABLE: 50%  Lock Wait: 50%, Computation: 50%  Scenarios3.lambda\n" +
            "worker-6  2000ms  8%  WAITING: 50%, RUNNABLE: 50%  Lock Wait: 50%, Computation: 50%  Scenarios3.lambda\n" +
            "worker-7  2000ms  8%  WAITING: 50%, RUNNABLE: 50%  Lock Wait: 50%, Computation: 50%  Scenarios3.lambda\n" +
            "worker-8  2000ms  8%  WAITING: 50%, RUNNABLE: 50%  Lock Wait: 50%, Computation: 50%  Scenarios3.lambda\n" +
            "worker-9  2000ms  8%  WAITING: 50%, RUNNABLE: 50%  Lock Wait: 50%, Computation: 50%  Scenarios3.lambda\n" +
            "worker-10  2000ms  8%  WAITING: 50%, RUNNABLE: 50%  Lock Wait: 50%, Computation: 50%  Scenarios3.lambda\n" +
            "worker-11  2000ms  8%  WAITING: 50%, RUNNABLE: 50%  Lock Wait: 50%, Computation: 50%  Scenarios3.lambda\n" +
            "worker-12  2000ms  8%  WAITING: 50%, RUNNABLE: 50%  Lock Wait: 50%, Computation: 50%  Scenarios3.lambda\n";
        String result = ContextCompressor.compress(input);
        assertFalse(result.contains("hidden-waiting-threads"),
            "no hidden note when only 1 thread hidden (housekeeping): " + result);
    }
}
