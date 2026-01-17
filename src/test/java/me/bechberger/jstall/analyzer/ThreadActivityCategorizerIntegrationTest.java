package me.bechberger.jstall.analyzer;

import me.bechberger.jthreaddump.model.StackFrame;
import me.bechberger.jthreaddump.model.ThreadInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests with real-world thread dump examples
 */
class ThreadActivityCategorizerIntegrationTest {

    @Test
    void testNettyKQueueSelector() {
        // Example: Netty Station Client 1-1
        ThreadInfo thread = new ThreadInfo(
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
                new StackFrame("sun.nio.ch.SelectorImpl", "lockAndDoSelect", "SelectorImpl.java", 130),
                new StackFrame("sun.nio.ch.SelectorImpl", "select", "SelectorImpl.java", 142),
                new StackFrame("io.netty.channel.nio.SelectedSelectionKeySetSelector", "select", "SelectedSelectionKeySetSelector.java", 62)
            ),
            List.of(),
            null,
            null
        );

        assertEquals(ThreadActivityCategorizer.Category.NETWORK,
            ThreadActivityCategorizer.categorize(thread),
            "KQueue.poll should be categorized as Network");
    }

    @Test
    void testFileInputStreamRead() {
        // Example: BaseDataReader: output stream of fsnotifier
        ThreadInfo thread = new ThreadInfo(
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
            ThreadActivityCategorizer.categorize(thread),
            "FileInputStream.read should be categorized as I/O Read");
    }

    @Test
    void testProcessReaper() {
        // Example: process reaper (pid 2881)
        ThreadInfo thread = new ThreadInfo(
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
            ThreadActivityCategorizer.categorize(thread),
            "ProcessHandleImpl.waitForProcessExit0 should be categorized as External Process");
    }

    @Test
    void testWatchServiceWithDeepStack() {
        // Example: DefaultDispatcher-worker-10 with WatchService deep in stack
        ThreadInfo thread = new ThreadInfo(
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
                new StackFrame("java.util.concurrent.ForkJoinPool", "managedBlock", "ForkJoinPool.java", 3961)
            ),
            List.of(),
            null,
            null
        );

        // Should match on Unsafe.park which is LOCK_WAIT (first match within 5 frames)
        assertEquals(ThreadActivityCategorizer.Category.LOCK_WAIT,
            ThreadActivityCategorizer.categorize(thread),
            "Unsafe.park should be categorized as Lock Wait");
    }

    @Test
    void testUnixDomainSocketAccept() {
        // Example: External Command Listener
        ThreadInfo thread = new ThreadInfo(
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
                new StackFrame("sun.nio.ch.ServerSocketChannelImpl", "implAccept", "ServerSocketChannelImpl.java", 427),
                new StackFrame("sun.nio.ch.ServerSocketChannelImpl", "accept", "ServerSocketChannelImpl.java", 399)
            ),
            List.of(),
            null,
            null
        );

        assertEquals(ThreadActivityCategorizer.Category.NETWORK_READ,
            ThreadActivityCategorizer.categorize(thread),
            "UnixDomainSockets.accept should be categorized as Network Read");
    }

    @Test
    void testComputationWithUnknownTopFrame() {
        // Test that if top frame is uncategorized but thread is RUNNABLE, it's Computation
        ThreadInfo thread = new ThreadInfo(
            "worker-thread",
            1L,
            null,
            5,
            false,
            Thread.State.RUNNABLE,
            100.0,
            100.0,
            List.of(
                new StackFrame("com.example.MyApp", "processData", "MyApp.java", 100),
                new StackFrame("com.example.MyApp", "compute", "MyApp.java", 50)
            ),
            List.of(),
            null,
            null
        );

        assertEquals(ThreadActivityCategorizer.Category.COMPUTATION,
            ThreadActivityCategorizer.categorize(thread),
            "Uncategorized RUNNABLE thread should be Computation");
    }

    @Test
    void testDeepIOReadDetection() {
        // Test that I/O read is detected even when not at the top of the stack
        ThreadInfo thread = new ThreadInfo(
            "reader-thread",
            1L,
            null,
            5,
            false,
            Thread.State.RUNNABLE,
            10.0,
            100.0,
            List.of(
                new StackFrame("com.example.Wrapper", "wrapperMethod", "Wrapper.java", 10),
                new StackFrame("com.example.AnotherWrapper", "anotherMethod", "AnotherWrapper.java", 20),
                new StackFrame("java.io.BufferedReader", "read", "BufferedReader.java", 100)
            ),
            List.of(),
            null,
            null
        );

        assertEquals(ThreadActivityCategorizer.Category.IO_READ,
            ThreadActivityCategorizer.categorize(thread),
            "I/O Read should be detected even with uncategorized frames above it");
    }

    @Test
    void testSocketReadOverIORead() {
        // Test that more specific Network Read takes precedence over generic I/O Read
        ThreadInfo thread = new ThreadInfo(
            "socket-thread",
            1L,
            null,
            5,
            false,
            Thread.State.RUNNABLE,
            10.0,
            100.0,
            List.of(
                new StackFrame("java.net.SocketInputStream", "read", "SocketInputStream.java", 100),
                new StackFrame("java.io.BufferedInputStream", "read", "BufferedInputStream.java", 200)
            ),
            List.of(),
            null,
            null
        );

        assertEquals(ThreadActivityCategorizer.Category.NETWORK_READ,
            ThreadActivityCategorizer.categorize(thread),
            "Network Read should take precedence as it's more specific");
    }

    @Test
    void testNetworkOverSocketRead() {
        // Test that generic Network (selector) takes precedence when present
        ThreadInfo thread = new ThreadInfo(
            "nio-thread",
            1L,
            null,
            5,
            false,
            Thread.State.RUNNABLE,
            10.0,
            100.0,
            List.of(
                new StackFrame("sun.nio.ch.EPollSelectorImpl", "doSelect", "EPollSelectorImpl.java", 100),
                new StackFrame("sun.nio.ch.SocketChannelImpl", "read", "SocketChannelImpl.java", 200)
            ),
            List.of(),
            null,
            null
        );

        // Since EPollSelectorImpl.doSelect is not read/write specific, it should be generic NETWORK
        // But if we check NETWORK_READ first, SocketChannelImpl.read would match
        // However, rules are checked in order, and EPollSelectorImpl should match NETWORK first
        assertEquals(ThreadActivityCategorizer.Category.NETWORK,
            ThreadActivityCategorizer.categorize(thread),
            "Network selector should be categorized as general Network (not Network Read)");
    }

    @Test
    void testEPollSelector() {
        // Test EPoll selector (Linux)
        ThreadInfo thread = new ThreadInfo(
            "epoll-thread",
            1L,
            null,
            5,
            false,
            Thread.State.RUNNABLE,
            10.0,
            100.0,
            List.of(
                new StackFrame("sun.nio.ch.EPoll", "wait", "EPoll.java", 100)
            ),
            List.of(),
            null,
            null
        );

        assertEquals(ThreadActivityCategorizer.Category.NETWORK,
            ThreadActivityCategorizer.categorize(thread),
            "EPoll.wait should be categorized as Network");
    }

    @Test
    void testNettyChannel() {
        // Test Netty channel
        ThreadInfo thread = new ThreadInfo(
            "netty-worker",
            1L,
            null,
            5,
            false,
            Thread.State.RUNNABLE,
            10.0,
            100.0,
            List.of(
                new StackFrame("io.netty.channel.nio.NioEventLoop", "run", "NioEventLoop.java", 100)
            ),
            List.of(),
            null,
            null
        );

        assertEquals(ThreadActivityCategorizer.Category.NETWORK,
            ThreadActivityCategorizer.categorize(thread),
            "Netty NIO channel should be categorized as Network");
    }

    @Test
    void testWatchServiceTake() {
        // Test WatchService.take directly at top
        ThreadInfo thread = new ThreadInfo(
            "file-watcher",
            1L,
            null,
            5,
            false,
            Thread.State.WAITING,
            10.0,
            100.0,
            List.of(
                new StackFrame("sun.nio.fs.AbstractWatchService", "take", "AbstractWatchService.java", 118)
            ),
            List.of(),
            null,
            null
        );

        assertEquals(ThreadActivityCategorizer.Category.NETWORK,
            ThreadActivityCategorizer.categorize(thread),
            "WatchService.take should be categorized as Network (file system monitoring)");
    }
}