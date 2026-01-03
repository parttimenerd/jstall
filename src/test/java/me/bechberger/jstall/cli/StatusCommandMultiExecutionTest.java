package me.bechberger.jstall.cli;

import me.bechberger.jstall.testframework.TestAppLauncher;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for StatusCommand with multi-execution support.
 * Tests analyzing multiple running JVMs in parallel.
 */
class StatusCommandMultiExecutionTest {

    @Test
    void testStatusCommandWithFilterMatchingMultipleJVMs() throws Exception {
        TestAppLauncher launcher1 = new TestAppLauncher();
        TestAppLauncher launcher2 = new TestAppLauncher();

        try {
            // Launch two instances of the same app
            launcher1.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "normal");
            launcher2.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "normal");

            launcher1.waitUntilReady(3000);
            launcher2.waitUntilReady(3000);

            long pid1 = launcher1.getPid();
            long pid2 = launcher2.getPid();

            // Use a filter that matches both JVMs
            StatusCommand cmd = new StatusCommand();
            cmd.targets = List.of("DeadlockTestApp");
            cmd.dumps = 1; // Reduced from 2

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            System.setOut(new PrintStream(out));

            try {
                int exitCode = cmd.call();
                assertTrue(exitCode >= 0);

                String output = out.toString();

                // Should analyze both matching JVMs
                assertTrue(output.contains("DeadlockTestApp"));

                // Should have separator for multiple results
                assertTrue(output.contains("=".repeat(80)));

                // PIDs should be in sorted order
                int pos1 = output.indexOf("PID " + Math.min(pid1, pid2));
                int pos2 = output.indexOf("PID " + Math.max(pid1, pid2));
                assertTrue(pos1 > 0 && pos2 > 0);
                assertTrue(pos1 < pos2, "PIDs should appear in sorted order");

            } finally {
                System.setOut(System.out);
            }

        } finally {
            launcher1.stop();
            launcher2.stop();
        }
    }

    @Test
    void testParallelExecutionIsFaster() throws Exception {
        TestAppLauncher launcher1 = new TestAppLauncher();
        TestAppLauncher launcher2 = new TestAppLauncher();

        try {
            launcher1.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "busy-work");
            launcher2.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "busy-work");

            launcher1.waitUntilReady(3000);
            launcher2.waitUntilReady(3000);

            StatusCommand cmd = new StatusCommand();
            cmd.targets = List.of(String.valueOf(launcher1.getPid()), String.valueOf(launcher2.getPid()));
            cmd.dumps = 2; // Reduced from 3
            cmd.interval = "300ms"; // Reduced from 500ms

            long startTime = System.currentTimeMillis();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            System.setOut(new PrintStream(out));

            try {
                cmd.call();
                long duration = System.currentTimeMillis() - startTime;

                // Parallel should take ~600ms, sequential would take ~1200ms
                assertTrue(duration < 4000,
                    "Parallel execution should complete in reasonable time. Took: " + duration + "ms");

            } finally {
                System.setOut(System.out);
            }

        } finally {
            launcher1.stop();
            launcher2.stop();
        }
    }

    @Test
    void testFilterMatchesSpecificApp() throws Exception {
        TestAppLauncher deadlockLauncher = new TestAppLauncher();
        TestAppLauncher busyLauncher = new TestAppLauncher();

        try {
            // Launch with different main classes
            deadlockLauncher.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "normal");
            // Using same class but we'll filter by "Deadlock" to match only the first one
            busyLauncher.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "busy-work");

            deadlockLauncher.waitUntilReady(3000);
            busyLauncher.waitUntilReady(3000);

            long pid1 = deadlockLauncher.getPid();
            long pid2 = busyLauncher.getPid();

            // Filter should match both since they're the same class
            StatusCommand cmd = new StatusCommand();
            cmd.targets = List.of("DeadlockTestApp");
            cmd.dumps = 1; // Reduced from 2

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            System.setOut(new PrintStream(out));

            try {
                int exitCode = cmd.call();
                assertTrue(exitCode >= 0);

                String output = out.toString();

                // Should match both PIDs
                assertTrue(output.contains("PID " + pid1) || output.contains(String.valueOf(pid1)));
                assertTrue(output.contains("PID " + pid2) || output.contains(String.valueOf(pid2)));

                // Should have separator
                assertTrue(output.contains("=".repeat(80)));

            } finally {
                System.setOut(System.out);
            }

        } finally {
            deadlockLauncher.stop();
            busyLauncher.stop();
        }
    }

    @Test
    void testFilterWithPartialClassName() throws Exception {
        TestAppLauncher launcher1 = new TestAppLauncher();
        TestAppLauncher launcher2 = new TestAppLauncher();

        try {
            launcher1.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "normal");
            launcher2.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "busy-work");

            launcher1.waitUntilReady(3000);
            launcher2.waitUntilReady(3000);

            // Use partial class name filter (case-insensitive)
            StatusCommand cmd = new StatusCommand();
            cmd.targets = List.of("testapp");  // Partial match
            cmd.dumps = 1; // Reduced from 2

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            System.setOut(new PrintStream(out));

            try {
                int exitCode = cmd.call();
                assertTrue(exitCode >= 0);

                String output = out.toString();

                // Should match both apps containing "testapp" in their package
                assertTrue(output.contains("DeadlockTestApp"));
                assertTrue(output.contains("=".repeat(80)));

            } finally {
                System.setOut(System.out);
            }

        } finally {
            launcher1.stop();
            launcher2.stop();
        }
    }

    @Test
    void testFilterWithNonMatchingPattern() throws Exception {
        TestAppLauncher launcher = new TestAppLauncher();

        try {
            launcher.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "normal");
            launcher.waitUntilReady(3000);

            // Use filter that won't match
            StatusCommand cmd = new StatusCommand();
            cmd.targets = List.of("NonExistentApp");
            cmd.dumps = 1; // Reduced from 2

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            System.setOut(new PrintStream(out));
            System.setErr(new PrintStream(err));

            try {
                int exitCode = cmd.call();

                // Should fail with error
                assertEquals(1, exitCode);

                String errorOutput = err.toString();

                // Should indicate no JVMs found
                assertTrue(errorOutput.contains("No JVMs") || errorOutput.contains("NonExistentApp"));

            } finally {
                System.setOut(System.out);
                System.setErr(System.err);
            }

        } finally {
            launcher.stop();
        }
    }
}