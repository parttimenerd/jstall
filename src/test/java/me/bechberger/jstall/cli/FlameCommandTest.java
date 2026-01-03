package me.bechberger.jstall.cli;

import me.bechberger.jstall.testframework.TestAppLauncher;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FlameCommand - verifying it supports filtering but only for a single JVM.
 */
class FlameCommandTest {

    @Test
    void testFlameCommandWithoutTarget() throws Exception {
        FlameCommand cmd = new FlameCommand();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));

        try {
            int exitCode = cmd.call();

            // Should fail without target
            assertEquals(1, exitCode);

            String output = out.toString();
            // Should show usage and available JVMs
            assertTrue(output.contains("Usage") || output.contains("JVM"));

        } finally {
            System.setOut(System.out);
        }
    }

    @Test
    void testFlameCommandWithFilterMatchingSingleJVM() throws Exception {
        TestAppLauncher launcher = new TestAppLauncher();

        try {
            launcher.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "normal");
            launcher.waitUntilReady(5000);

            long pid = launcher.getPid();

            // Use filter that should match only this JVM
            FlameCommand cmd = new FlameCommand();
            cmd.target = "DeadlockTestApp";
            cmd.duration = "1s";

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            System.setOut(new PrintStream(out));

            try {
                int exitCode = cmd.call();

                String output = out.toString();

                // Should resolve to the single matching JVM
                assertTrue(output.contains(String.valueOf(pid)) ||
                          output.contains("DeadlockTestApp"),
                    "Should mention the resolved PID or class name");

            } finally {
                System.setOut(System.out);
            }

        } finally {
            launcher.stop();
        }
    }

    @Test
    void testFlameCommandRejectsMultipleMatches() throws Exception {
        TestAppLauncher launcher1 = new TestAppLauncher();
        TestAppLauncher launcher2 = new TestAppLauncher();

        try {
            // Launch two JVMs with same class name
            launcher1.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "normal");
            launcher2.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "busy-work");

            launcher1.waitUntilReady(5000);
            launcher2.waitUntilReady(5000);

            long pid1 = launcher1.getPid();
            long pid2 = launcher2.getPid();

            // Use filter that matches both
            FlameCommand cmd = new FlameCommand();
            cmd.target = "DeadlockTestApp";

            ByteArrayOutputStream err = new ByteArrayOutputStream();
            System.setErr(new PrintStream(err));

            try {
                int exitCode = cmd.call();

                // Should fail
                assertEquals(1, exitCode);

                String errorOutput = err.toString();

                // Should indicate multiple matches
                assertTrue(errorOutput.contains("does not support multiple targets") ||
                          errorOutput.contains("matched"),
                    "Should reject multiple matches");

                // Should list both PIDs
                assertTrue(errorOutput.contains(String.valueOf(pid1)));
                assertTrue(errorOutput.contains(String.valueOf(pid2)));

            } finally {
                System.setErr(System.err);
            }

        } finally {
            launcher1.stop();
            launcher2.stop();
        }
    }

    @Test
    void testFlameCommandWithNonMatchingFilter() throws Exception {
        TestAppLauncher launcher = new TestAppLauncher();

        try {
            launcher.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "normal");
            launcher.waitUntilReady(5000);

            // Use filter that won't match
            FlameCommand cmd = new FlameCommand();
            cmd.target = "NonExistentApp";

            ByteArrayOutputStream err = new ByteArrayOutputStream();
            System.setErr(new PrintStream(err));

            try {
                int exitCode = cmd.call();

                // Should fail
                assertEquals(1, exitCode);

                String errorOutput = err.toString();

                // Should indicate no JVMs found
                assertTrue(errorOutput.contains("No JVMs") ||
                          errorOutput.contains("NonExistentApp"));

            } finally {
                System.setErr(System.err);
            }

        } finally {
            launcher.stop();
        }
    }
}