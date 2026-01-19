package me.bechberger.jstall.analyzer;

import me.bechberger.jthreaddump.model.StackFrame;
import me.bechberger.jthreaddump.model.ThreadInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ThreadActivityCategorizerTest {

    @Test
    void testIOReadCategorization() {
        ThreadInfo thread = createThread(
            new StackFrame("java.io.FileInputStream", "read", "FileInputStream.java", 100)
        );

        assertEquals(ThreadActivityCategorizer.Category.IO_READ,
            ThreadActivityCategorizer.categorize(thread));
    }

    @Test
    void testIOWriteCategorization() {
        ThreadInfo thread = createThread(
            new StackFrame("java.io.FileOutputStream", "write", "FileOutputStream.java", 100)
        );

        assertEquals(ThreadActivityCategorizer.Category.IO_WRITE,
            ThreadActivityCategorizer.categorize(thread));
    }

    @Test
    void testSocketReadCategorization() {
        ThreadInfo thread = createThread(
            new StackFrame("java.net.SocketInputStream", "read", "SocketInputStream.java", 100)
        );

        assertEquals(ThreadActivityCategorizer.Category.NETWORK_READ,
            ThreadActivityCategorizer.categorize(thread));
    }

    @Test
    void testSocketWriteCategorization() {
        ThreadInfo thread = createThread(
            new StackFrame("java.net.SocketOutputStream", "write", "SocketOutputStream.java", 100)
        );

        assertEquals(ThreadActivityCategorizer.Category.NETWORK_WRITE,
            ThreadActivityCategorizer.categorize(thread));
    }

    @Test
    void testLockWaitCategorization() {
        ThreadInfo thread = createThread(
            new StackFrame("jdk.internal.misc.Unsafe", "park", "Unsafe.java", 100)
        );

        assertEquals(ThreadActivityCategorizer.Category.LOCK_WAIT,
            ThreadActivityCategorizer.categorize(thread));
    }

    @Test
    void testSleepCategorization() {
        ThreadInfo thread = createThread(
            new StackFrame("java.lang.Thread", "sleep", "Thread.java", 100)
        );

        assertEquals(ThreadActivityCategorizer.Category.SLEEP,
            ThreadActivityCategorizer.categorize(thread));
    }

    @Test
    void testComputationCategorization() {
        ThreadInfo thread = createThread(
            Thread.State.RUNNABLE,
            new StackFrame("com.example.MyClass", "compute", "MyClass.java", 100)
        );

        assertEquals(ThreadActivityCategorizer.Category.COMPUTATION,
            ThreadActivityCategorizer.categorize(thread));
    }

    @Test
    void testUnknownCategorization() {
        ThreadInfo thread = createThread(
            Thread.State.WAITING,
            new StackFrame("com.example.MyClass", "someMethod", "MyClass.java", 100)
        );

        assertEquals(ThreadActivityCategorizer.Category.UNKNOWN,
            ThreadActivityCategorizer.categorize(thread));
    }

    @Test
    void testEmptyStackTrace() {
        ThreadInfo thread = new ThreadInfo(
            "test-thread",
            1L,
            null,
            null,
            null,
            Thread.State.RUNNABLE,
            null,
            null,
            List.of(),
            List.of(),
            null,
            null
        );

        assertEquals(ThreadActivityCategorizer.Category.UNKNOWN,
            ThreadActivityCategorizer.categorize(thread));
    }

    @Test
    void testCategorizeMultiple() {
        List<ThreadInfo> threads = List.of(
            createThread(new StackFrame("java.io.FileInputStream", "read", "FileInputStream.java", 100)),
            createThread(new StackFrame("java.io.FileInputStream", "read", "FileInputStream.java", 100)),
            createThread(new StackFrame("java.io.FileOutputStream", "write", "FileOutputStream.java", 100))
        );

        Map<ThreadActivityCategorizer.Category, Integer> distribution =
            ThreadActivityCategorizer.categorizeMultiple(threads);

        assertEquals(2, distribution.get(ThreadActivityCategorizer.Category.IO_READ));
        assertEquals(1, distribution.get(ThreadActivityCategorizer.Category.IO_WRITE));
    }

    @Test
    void testFormatDistribution() {
        Map<ThreadActivityCategorizer.Category, Integer> distribution = Map.of(
            ThreadActivityCategorizer.Category.NETWORK_READ, 2,
            ThreadActivityCategorizer.Category.NETWORK_WRITE, 1
        );

        String formatted = ThreadActivityCategorizer.formatDistribution(distribution, 3);

        assertTrue(formatted.contains("Network Read"));
        assertTrue(formatted.contains("67%"));
        assertTrue(formatted.contains("Network Write"));
        assertTrue(formatted.contains("33%"));
    }

    @Test
    void testDatabaseCategorization() {
        ThreadInfo jdbcThread = createThread(
            Thread.State.RUNNABLE,
            new StackFrame("java.sql.Connection", "prepareStatement", "Connection.java", 100)
        );

        assertEquals(ThreadActivityCategorizer.Category.DB,
            ThreadActivityCategorizer.categorize(jdbcThread));

        ThreadInfo resultSetThread = createThread(
            Thread.State.RUNNABLE,
            new StackFrame("java.sql.ResultSet", "next", "ResultSet.java", 100)
        );

        assertEquals(ThreadActivityCategorizer.Category.DB,
            ThreadActivityCategorizer.categorize(resultSetThread));
    }

    @Test
    void testFormatDistributionSingleCategory100Percent() {
        Map<ThreadActivityCategorizer.Category, Integer> distribution = Map.of(
            ThreadActivityCategorizer.Category.COMPUTATION, 5
        );

        String formatted = ThreadActivityCategorizer.formatDistribution(distribution, 5);

        assertEquals("Computation", formatted);
    }

    @Test
    void testDeepStackFrameMatching() {
        // Test that categorizer looks up to 5 frames deep
        ThreadInfo thread = new ThreadInfo(
            "test-thread",
            1L,
            null,
            null,
            null,
            Thread.State.RUNNABLE,
            null,
            null,
            List.of(
                new StackFrame("com.example.Wrapper1", "method1", "Wrapper1.java", 10),
                new StackFrame("com.example.Wrapper2", "method2", "Wrapper2.java", 20),
                new StackFrame("com.example.Wrapper3", "method3", "Wrapper3.java", 30),
                new StackFrame("java.io.FileInputStream", "read", "FileInputStream.java", 100)
            ),
            List.of(),
            null,
            null
        );

        assertEquals(ThreadActivityCategorizer.Category.IO_READ,
            ThreadActivityCategorizer.categorize(thread));
    }

    @Test
    void testNetworkCategorization() {
        // Test KQueue selector (macOS)
        ThreadInfo kqueueThread = createThread(
            Thread.State.RUNNABLE,
            new StackFrame("sun.nio.ch.KQueue", "poll", null, null)
        );
        assertEquals(ThreadActivityCategorizer.Category.NETWORK,
            ThreadActivityCategorizer.categorize(kqueueThread));

        // Test EPoll selector (Linux)
        ThreadInfo epollThread = createThread(
            Thread.State.RUNNABLE,
            new StackFrame("sun.nio.ch.EPollSelectorImpl", "doSelect", "EPollSelectorImpl.java", 100)
        );
        assertEquals(ThreadActivityCategorizer.Category.NETWORK,
            ThreadActivityCategorizer.categorize(epollThread));

        // Test Netty
        ThreadInfo nettyThread = createThread(
            Thread.State.RUNNABLE,
            new StackFrame("io.netty.channel.nio.SelectedSelectionKeySetSelector", "select", "SelectedSelectionKeySetSelector.java", 62)
        );
        assertEquals(ThreadActivityCategorizer.Category.NETWORK,
            ThreadActivityCategorizer.categorize(nettyThread));

        // Test WatchService
        ThreadInfo watchThread = createThread(
            Thread.State.WAITING,
            new StackFrame("sun.nio.fs.AbstractWatchService", "take", "AbstractWatchService.java", 118)
        );
        assertEquals(ThreadActivityCategorizer.Category.NETWORK,
            ThreadActivityCategorizer.categorize(watchThread));
    }

    @Test
    void testExternalProcessCategorization() {
        ThreadInfo processThread = createThread(
            Thread.State.RUNNABLE,
            new StackFrame("java.lang.ProcessHandleImpl", "waitForProcessExit0", null, null)
        );

        assertEquals(ThreadActivityCategorizer.Category.EXTERNAL_PROCESS,
            ThreadActivityCategorizer.categorize(processThread));
    }

    @Test
    void testUnixDomainSocketAccept() {
        ThreadInfo unixSocketThread = createThread(
            Thread.State.RUNNABLE,
            new StackFrame("sun.nio.ch.UnixDomainSockets", "accept0", null, null)
        );

        assertEquals(ThreadActivityCategorizer.Category.NETWORK_READ,
            ThreadActivityCategorizer.categorize(unixSocketThread));
    }

    @Test
    void testRealWorldNettyExample() {
        // Real example from user's request
        ThreadInfo nettyThread = new ThreadInfo(
            "Netty Station Client 1-1",
            88L,
            165639L,
            5,
            true,
            Thread.State.RUNNABLE,
            30.67,
            40547.16,
            List.of(
                new StackFrame("sun.nio.ch.KQueue", "poll", null, null),
                new StackFrame("sun.nio.ch.KQueueSelectorImpl", "doSelect", "KQueueSelectorImpl.java", 125),
                new StackFrame("sun.nio.ch.SelectorImpl", "lockAndDoSelect", "SelectorImpl.java", 130)
            ),
            List.of(),
            null,
            null
        );

        assertEquals(ThreadActivityCategorizer.Category.NETWORK,
            ThreadActivityCategorizer.categorize(nettyThread));
    }

    @Test
    void testRealWorldFileInputStreamExample() {
        // Real example from user's request
        ThreadInfo ioThread = new ThreadInfo(
            "BaseDataReader: output stream of fsnotifier",
            91L,
            139779L,
            4,
            false,
            Thread.State.RUNNABLE,
            7965.14,
            40546.95,
            List.of(
                new StackFrame("java.io.FileInputStream", "readBytes", null, null),
                new StackFrame("java.io.FileInputStream", "implRead", "FileInputStream.java", 379),
                new StackFrame("java.io.FileInputStream", "read", "FileInputStream.java", 371)
            ),
            List.of(),
            null,
            null
        );

        assertEquals(ThreadActivityCategorizer.Category.IO_READ,
            ThreadActivityCategorizer.categorize(ioThread));
    }

    @Test
    void testRealWorldProcessReaperExample() {
        // Real example from user's request
        ThreadInfo processThread = new ThreadInfo(
            "process reaper (pid 2881)",
            149L,
            176387L,
            10,
            true,
            Thread.State.RUNNABLE,
            10.46,
            40546.27,
            List.of(
                new StackFrame("java.lang.ProcessHandleImpl", "waitForProcessExit0", null, null),
                new StackFrame("java.lang.ProcessHandleImpl$1", "run", "ProcessHandleImpl.java", 163),
                new StackFrame("java.util.concurrent.ThreadPoolExecutor", "runWorker", "ThreadPoolExecutor.java", 1144)
            ),
            List.of(),
            null,
            null
        );

        assertEquals(ThreadActivityCategorizer.Category.EXTERNAL_PROCESS,
            ThreadActivityCategorizer.categorize(processThread));
    }

    @Test
    void testRealWorldWatchServiceExample() {
        // Real example from user's request - DefaultDispatcher with WatchService
        ThreadInfo watchThread = new ThreadInfo(
            "DefaultDispatcher-worker-10",
            30L,
            41219L,
            5,
            true,
            Thread.State.WAITING,
            263.46,
            40549.54,
            List.of(
                new StackFrame("jdk.internal.misc.Unsafe", "park", null, null),
                new StackFrame("java.util.concurrent.locks.LockSupport", "park", "LockSupport.java", 371),
                new StackFrame("java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionNode", "block", "AbstractQueuedSynchronizer.java", 519),
                new StackFrame("java.util.concurrent.ForkJoinPool", "unmanagedBlock", "ForkJoinPool.java", 4013),
                new StackFrame("java.util.concurrent.ForkJoinPool", "managedBlock", "ForkJoinPool.java", 3961),
                new StackFrame("java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject", "await", "AbstractQueuedSynchronizer.java", 1746),
                new StackFrame("java.util.concurrent.LinkedBlockingDeque", "takeFirst", "LinkedBlockingDeque.java", 485),
                new StackFrame("java.util.concurrent.LinkedBlockingDeque", "take", "LinkedBlockingDeque.java", 673),
                new StackFrame("sun.nio.fs.AbstractWatchService", "take", "AbstractWatchService.java", 118),
                new StackFrame("com.intellij.platform.core.nio.fs.MultiRoutingWatchServiceDelegate", "take", "MultiRoutingWatchServiceDelegate.java", 41)
            ),
            List.of(),
            null,
            null
        );

        // Should find WatchService.take within the first 5 frames? No, it's at position 8
        // So this should match on Unsafe.park first, which is LOCK_WAIT
        // But let's verify our categorizer depth
        assertEquals(ThreadActivityCategorizer.Category.LOCK_WAIT,
            ThreadActivityCategorizer.categorize(watchThread));
    }

    @Test
    void testRealWorldUnixDomainSocketExample() {
        // Real example from user's request
        ThreadInfo socketThread = new ThreadInfo(
            "External Command Listener",
            36L,
            39683L,
            5,
            true,
            Thread.State.RUNNABLE,
            0.17,
            40549.43,
            List.of(
                new StackFrame("sun.nio.ch.UnixDomainSockets", "accept0", null, null),
                new StackFrame("sun.nio.ch.UnixDomainSockets", "accept", "UnixDomainSockets.java", 173),
                new StackFrame("sun.nio.ch.ServerSocketChannelImpl", "implAccept", "ServerSocketChannelImpl.java", 427)
            ),
            List.of(),
            null,
            null
        );

        assertEquals(ThreadActivityCategorizer.Category.NETWORK_READ,
            ThreadActivityCategorizer.categorize(socketThread));
    }

    @Test
    void testNativeCategorization() {
        ThreadInfo nativeThread = createThread(
            Thread.State.RUNNABLE,
            new StackFrame("java.net.PlainSocketImpl", "socketAccept", null, null, true)
        );

        assertEquals(ThreadActivityCategorizer.Category.NATIVE,
            ThreadActivityCategorizer.categorize(nativeThread));
    }

    @Test
    void testCategoryGroups() {
        // Test Networking group
        assertEquals(ThreadActivityCategorizer.CategoryGroup.NETWORKING,
            ThreadActivityCategorizer.Category.NETWORK_READ.getGroup());
        assertEquals(ThreadActivityCategorizer.CategoryGroup.NETWORKING,
            ThreadActivityCategorizer.Category.NETWORK_WRITE.getGroup());
        assertEquals(ThreadActivityCategorizer.CategoryGroup.NETWORKING,
            ThreadActivityCategorizer.Category.NETWORK.getGroup());

        // Test I/O group
        assertEquals(ThreadActivityCategorizer.CategoryGroup.IO,
            ThreadActivityCategorizer.Category.IO_READ.getGroup());
        assertEquals(ThreadActivityCategorizer.CategoryGroup.IO,
            ThreadActivityCategorizer.Category.IO_WRITE.getGroup());
        assertEquals(ThreadActivityCategorizer.CategoryGroup.IO,
            ThreadActivityCategorizer.Category.IO.getGroup());
        assertEquals(ThreadActivityCategorizer.CategoryGroup.IO,
            ThreadActivityCategorizer.Category.DB.getGroup());

        // Test Locking group
        assertEquals(ThreadActivityCategorizer.CategoryGroup.LOCKING,
            ThreadActivityCategorizer.Category.LOCK_WAIT.getGroup());
        assertEquals(ThreadActivityCategorizer.CategoryGroup.LOCKING,
            ThreadActivityCategorizer.Category.SLEEP.getGroup());
        assertEquals(ThreadActivityCategorizer.CategoryGroup.LOCKING,
            ThreadActivityCategorizer.Category.PARK.getGroup());

        // Test Java group
        assertEquals(ThreadActivityCategorizer.CategoryGroup.JAVA,
            ThreadActivityCategorizer.Category.AWT.getGroup());
        assertEquals(ThreadActivityCategorizer.CategoryGroup.JAVA,
            ThreadActivityCategorizer.Category.TIMER.getGroup());
        assertEquals(ThreadActivityCategorizer.CategoryGroup.JAVA,
            ThreadActivityCategorizer.Category.VIRTUAL_THREAD.getGroup());
        assertEquals(ThreadActivityCategorizer.CategoryGroup.JAVA,
            ThreadActivityCategorizer.Category.FORK_JOIN.getGroup());
        assertEquals(ThreadActivityCategorizer.CategoryGroup.JAVA,
            ThreadActivityCategorizer.Category.EXTERNAL_PROCESS.getGroup());

        // Test Native group
        assertEquals(ThreadActivityCategorizer.CategoryGroup.NATIVE,
            ThreadActivityCategorizer.Category.NATIVE.getGroup());

        // Test Computation group
        assertEquals(ThreadActivityCategorizer.CategoryGroup.COMPUTATION,
            ThreadActivityCategorizer.Category.COMPUTATION.getGroup());

        // Test Unknown group
        assertEquals(ThreadActivityCategorizer.CategoryGroup.UNKNOWN,
            ThreadActivityCategorizer.Category.UNKNOWN.getGroup());
    }

    // Helper methods

    private ThreadInfo createThread(StackFrame... frames) {
        return createThread(Thread.State.WAITING, frames);
    }

    private ThreadInfo createThread(Thread.State state, StackFrame... frames) {
        return new ThreadInfo(
            "test-thread",
            1L,
            null,
            null,
            null,
            state,
            null,
            null,
            List.of(frames),
            List.of(),
            null,
            null
        );
    }
}