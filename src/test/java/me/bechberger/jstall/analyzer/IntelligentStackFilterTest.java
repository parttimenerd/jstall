package me.bechberger.jstall.analyzer;

import me.bechberger.jthreaddump.model.StackFrame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IntelligentStackFilterTest {

    @Test
    void testSimpleApplicationCode() {
        List<StackFrame> frames = List.of(
            new StackFrame("com.example.MyApp", "businessLogic", "MyApp.java", 100),
            new StackFrame("com.example.Service", "process", "Service.java", 50)
        );

        List<IntelligentStackFilter.FilteredFrame> result =
            IntelligentStackFilter.filterStackTrace(frames, 10);

        assertEquals(2, result.size());
        assertNotNull(result.get(0).frame);
        assertNotNull(result.get(1).frame);
    }

    @Test
    void testCollapsesInternalFrames() {
        List<StackFrame> frames = List.of(
            new StackFrame("com.example.MyApp", "businessLogic", "MyApp.java", 100),
            new StackFrame("jdk.internal.reflect.Method", "invoke", "Method.java", 50),
            new StackFrame("sun.reflect.DelegatingMethodAccessorImpl", "invoke", "DelegatingMethodAccessorImpl.java", 25),
            new StackFrame("java.lang.reflect.Method", "invoke", "Method.java", 10),
            new StackFrame("com.example.Service", "process", "Service.java", 200)
        );

        List<IntelligentStackFilter.FilteredFrame> result =
            IntelligentStackFilter.filterStackTrace(frames, 10);

        // Should have: app frame, collapsed marker, app frame
        assertEquals(3, result.size());
        assertNotNull(result.get(0).frame);
        assertEquals("com.example.MyApp", result.get(0).frame.className());

        assertNull(result.get(1).frame);
        assertTrue(result.get(1).isCollapsed);
        assertEquals(3, result.get(1).collapsedCount);

        assertNotNull(result.get(2).frame);
        assertEquals("com.example.Service", result.get(2).frame.className());
    }

    @Test
    void testPreservesImportantFrames() {
        List<StackFrame> frames = List.of(
            new StackFrame("com.example.MyApp", "readData", "MyApp.java", 100),
            new StackFrame("java.io.FileInputStream", "read", "FileInputStream.java", 200),
            new StackFrame("java.nio.channels.FileChannel", "read", "FileChannel.java", 150)
        );

        List<IntelligentStackFilter.FilteredFrame> result =
            IntelligentStackFilter.filterStackTrace(frames, 10);

        // All frames should be preserved (app + important I/O frames)
        assertEquals(3, result.size());
        assertTrue(result.stream().allMatch(f -> f.frame != null));
    }

    @Test
    void testNetworkFramesPreserved() {
        List<StackFrame> frames = List.of(
            new StackFrame("com.example.MyApp", "handleRequest", "MyApp.java", 100),
            new StackFrame("java.net.Socket", "read", "Socket.java", 200),
            new StackFrame("sun.nio.ch.SocketChannelImpl", "read", "SocketChannelImpl.java", 150)
        );

        List<IntelligentStackFilter.FilteredFrame> result =
            IntelligentStackFilter.filterStackTrace(frames, 10);

        // All frames should be preserved (app + important network frames)
        assertEquals(3, result.size());
        assertTrue(result.stream().allMatch(f -> f.frame != null));
    }

    @Test
    void testDatabaseFramesPreserved() {
        List<StackFrame> frames = List.of(
            new StackFrame("com.example.MyApp", "queryUsers", "MyApp.java", 100),
            new StackFrame("java.sql.Connection", "executeQuery", "Connection.java", 200),
            new StackFrame("java.sql.ResultSet", "next", "ResultSet.java", 150)
        );

        List<IntelligentStackFilter.FilteredFrame> result =
            IntelligentStackFilter.filterStackTrace(frames, 10);

        // All frames should be preserved (app + important DB frames)
        assertEquals(3, result.size());
        assertTrue(result.stream().allMatch(f -> f.frame != null));
    }

    @Test
    void testRespectsMaxDepth() {
        List<StackFrame> frames = List.of(
            new StackFrame("com.example.MyApp", "method1", "MyApp.java", 100),
            new StackFrame("com.example.MyApp", "method2", "MyApp.java", 200),
            new StackFrame("com.example.MyApp", "method3", "MyApp.java", 300),
            new StackFrame("com.example.MyApp", "method4", "MyApp.java", 400),
            new StackFrame("com.example.MyApp", "method5", "MyApp.java", 500)
        );

        List<IntelligentStackFilter.FilteredFrame> result =
            IntelligentStackFilter.filterStackTrace(frames, 3);

        // Should have 3 frames + 1 "more frames" marker
        assertEquals(4, result.size());
        assertNotNull(result.get(0).frame);
        assertNotNull(result.get(1).frame);
        assertNotNull(result.get(2).frame);

        assertNull(result.get(3).frame);
        assertFalse(result.get(3).isCollapsed);  // "more" marker, not "collapsed"
        assertEquals(2, result.get(3).collapsedCount);
    }

    @Test
    void testFormatting() {
        List<IntelligentStackFilter.FilteredFrame> frames = List.of(
            new IntelligentStackFilter.FilteredFrame(
                new StackFrame("com.example.MyApp", "method1", "MyApp.java", 100),
                0,
                false
            ),
            new IntelligentStackFilter.FilteredFrame(
                null,
                3,
                true  // collapsed internal
            ),
            new IntelligentStackFilter.FilteredFrame(
                new StackFrame("com.example.MyApp", "method2", "MyApp.java", 200),
                0,
                false
            )
        );

        String formatted = IntelligentStackFilter.formatFilteredStackTrace(frames, "  ");

        assertTrue(formatted.contains("com.example.MyApp.method1"));
        assertTrue(formatted.contains("3 internal frames omitted") || formatted.contains("3 internal frame omitted"));
        assertTrue(formatted.contains("com.example.MyApp.method2"));
    }

    @Test
    void testMixedApplicationAndInternal() {
        List<StackFrame> frames = List.of(
            new StackFrame("com.example.MyApp", "start", "MyApp.java", 100),
            new StackFrame("jdk.internal.reflect.Method", "invoke", "Method.java", 50),
            new StackFrame("sun.reflect.DelegatingMethodAccessorImpl", "invoke", "DelegatingMethodAccessorImpl.java", 25),
            new StackFrame("java.io.FileInputStream", "read", "FileInputStream.java", 200),  // Important
            new StackFrame("com.example.Utils", "readFile", "Utils.java", 300),
            new StackFrame("java.lang.Thread", "run", "Thread.java", 400)  // Internal but common
        );

        List<IntelligentStackFilter.FilteredFrame> result =
            IntelligentStackFilter.filterStackTrace(frames, 10);

        // Should have: app, collapsed, important I/O, app, internal
        assertEquals(5, result.size());
        assertNotNull(result.get(0).frame);  // MyApp.start
        assertNull(result.get(1).frame);     // collapsed (2 internal frames)
        assertNotNull(result.get(2).frame);  // FileInputStream.read (important)
        assertNotNull(result.get(3).frame);  // Utils.readFile
        assertNull(result.get(4).frame);     // collapsed (Thread.run)
    }
}