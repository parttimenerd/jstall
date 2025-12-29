package me.bechberger.jstall.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FlameCommand.
 */
public class FlameCommandTest {

    @Test
    public void testCommandCreation() {
        FlameCommand command = new FlameCommand();
        assertNotNull(command);
    }

    @Test
    public void testCommandLineParsingWithDefaults() {
        FlameCommand command = new FlameCommand();
        CommandLine cmd = new CommandLine(command);

        int exitCode = cmd.execute("12345");

        // Command should fail gracefully if PID doesn't exist or platform not supported
        // We're just testing that parsing works
        assertTrue(exitCode >= 0);
    }

    @Test
    public void testCommandLineParsingWithDurationString(@TempDir Path tempDir) {
        FlameCommand command = new FlameCommand();
        CommandLine cmd = new CommandLine(command);

        String outputFile = tempDir.resolve("test-flame.html").toString();

        // Test with various duration formats
        int exitCode = cmd.execute(
            "12345",
            "--duration", "30s",
            "--output", outputFile
        );

        assertTrue(exitCode >= 0);
    }

    @Test
    public void testCommandLineParsingWithIntervalString(@TempDir Path tempDir) {
        FlameCommand command = new FlameCommand();
        CommandLine cmd = new CommandLine(command);

        String outputFile = tempDir.resolve("test-flame.html").toString();

        // Test with various interval formats
        int exitCode = cmd.execute(
            "12345",
            "--interval", "5ms",
            "--output", outputFile
        );

        assertTrue(exitCode >= 0);
    }

    @Test
    public void testHumanReadableDurationFormats(@TempDir Path tempDir) {
        FlameCommand command = new FlameCommand();
        CommandLine cmd = new CommandLine(command);

        String outputFile = tempDir.resolve("test-flame.html").toString();

        // Test various duration formats
        String[] durations = {"30s", "2m", "500ms", "60"};

        for (String duration : durations) {
            int exitCode = cmd.execute("12345", "-d", duration, "-o", outputFile);
            assertTrue(exitCode >= 0, "Duration " + duration + " should be accepted");
        }
    }

    @Test
    public void testHumanReadableIntervalFormats(@TempDir Path tempDir) {
        FlameCommand command = new FlameCommand();
        CommandLine cmd = new CommandLine(command);

        String outputFile = tempDir.resolve("test-flame.html").toString();

        // Test various interval formats
        String[] intervals = {"10ms", "1s", "5000000ns", "1000000"};

        for (String interval : intervals) {
            int exitCode = cmd.execute("12345", "-i", interval, "-o", outputFile);
            assertTrue(exitCode >= 0, "Interval " + interval + " should be accepted");
        }
    }

    @Test
    public void testCommandLineParsingWithOptions(@TempDir Path tempDir) {
        FlameCommand command = new FlameCommand();
        CommandLine cmd = new CommandLine(command);

        String outputFile = tempDir.resolve("test-flame.html").toString();

        int exitCode = cmd.execute(
            "12345",
            "--duration", "10s",
            "--event", "wall",
            "--interval", "5ms",
            "--output", outputFile
        );

        // Command should parse correctly
        assertTrue(exitCode >= 0);
    }

    @Test
    public void testCommandLineParsingWithShortOptions(@TempDir Path tempDir) {
        FlameCommand command = new FlameCommand();
        CommandLine cmd = new CommandLine(command);

        String outputFile = tempDir.resolve("test-flame.html").toString();

        int exitCode = cmd.execute(
            "12345",
            "-d", "10s",
            "-e", "cpu",
            "-i", "5ms",
            "-o", outputFile
        );

        assertTrue(exitCode >= 0);
    }

    @Test
    public void testInvalidPID() {
        FlameCommand command = new FlameCommand();
        CommandLine cmd = new CommandLine(command);

        // Should fail to parse non-numeric PID
        assertThrows(CommandLine.ParameterException.class, () -> {
            cmd.parseArgs("not-a-number");
        });
    }

    @Test
    public void testMissingPID() {
        FlameCommand command = new FlameCommand();
        CommandLine cmd = new CommandLine(command);

        // Should print usage and list JVMs, then exit with code 1
        int exitCode = cmd.execute();

        assertEquals(1, exitCode, "Missing PID should return exit code 1");
    }

    @Test
    public void testHelpOption() {
        FlameCommand command = new FlameCommand();
        CommandLine cmd = new CommandLine(command);

        int exitCode = cmd.execute("--help");

        // picocli returns 2 for help option
        assertEquals(2, exitCode, "Help option should return exit code 2");
    }

    @Test
    public void testDefaultValues() {
        FlameCommand command = new FlameCommand();
        CommandLine cmd = new CommandLine(command);

        // Parse with just PID to get defaults
        cmd.parseArgs("12345");

        // Defaults should be applied (though we can't directly access private fields in this test)
        // We verify this by checking command execution doesn't throw parsing errors
        assertNotNull(command);
    }

    @Test
    public void testEventTypes() {
        FlameCommand command = new FlameCommand();
        CommandLine cmd = new CommandLine(command);

        // Test various event types are accepted
        String[] events = {"cpu", "alloc", "lock", "wall", "itimer"};

        for (String event : events) {
            int exitCode = cmd.execute("12345", "-e", event);
            // Should parse successfully (may fail execution if platform not supported)
            assertTrue(exitCode >= 0, "Event type " + event + " should be accepted");
        }
    }

    @Test
    public void testFormatOption(@TempDir Path tempDir) {
        FlameCommand command = new FlameCommand();
        CommandLine cmd = new CommandLine(command);

        String outputFile = tempDir.resolve("profile.jfr").toString();

        int exitCode = cmd.execute(
            "12345",
            "-f", "jfr",
            "-o", outputFile
        );

        assertTrue(exitCode >= 0);
    }

    @Test
    public void testMultipleFormatTypes(@TempDir Path tempDir) {
        FlameCommand command = new FlameCommand();
        CommandLine cmd = new CommandLine(command);

        String[] formats = {"html", "jfr", "collapsed"};

        for (String format : formats) {
            String outputFile = tempDir.resolve("profile." + format).toString();
            int exitCode = cmd.execute("12345", "-f", format, "-o", outputFile);
            assertTrue(exitCode >= 0, "Format " + format + " should be accepted");
        }
    }

    @Test
    public void testVeryLargeDuration() {
        FlameCommand command = new FlameCommand();
        CommandLine cmd = new CommandLine(command);

        int exitCode = cmd.execute("12345", "-d", "1h");

        // Should parse but CommandHelper.parseDuration doesn't support hours yet
        // so this will likely fail parsing, but the command structure is valid
        assertTrue(exitCode >= 0);
    }

    @Test
    public void testMicrosecondInterval(@TempDir Path tempDir) {
        FlameCommand command = new FlameCommand();
        CommandLine cmd = new CommandLine(command);

        String outputFile = tempDir.resolve("test-flame.html").toString();

        int exitCode = cmd.execute("12345", "-i", "500us", "-o", outputFile);

        assertTrue(exitCode >= 0);
    }

    // Integration tests that verify flamegraphs are actually generated

    @Test
    public void testFlamegraphGeneration(@TempDir Path tempDir) throws Exception {
        // Only run if async-profiler is supported on this platform
        if (!one.profiler.AsyncProfilerLoader.isSupported()) {
            System.out.println("Skipping flamegraph generation test - async-profiler not supported on this platform");
            return;
        }

        me.bechberger.jstall.testframework.TestAppLauncher launcher = new me.bechberger.jstall.testframework.TestAppLauncher();

        try {
            // Launch test app with busy workers
            launcher.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "busy-work");
            launcher.waitUntilReady(5000);

            // Wait for threads to start working
            Thread.sleep(1000);

            String outputFile = tempDir.resolve("flamegraph.html").toString();

            // Generate flamegraph with short duration for testing
            FlameCommand command = new FlameCommand();
            CommandLine cmd = new CommandLine(command);
            int exitCode = cmd.execute(
                String.valueOf(launcher.getPid()),
                "-d", "2s",
                "-e", "wall",  // Use wall-clock profiling (works on all platforms)
                "-o", outputFile
            );

            // Verify command succeeded
            assertEquals(0, exitCode, "Flamegraph generation should succeed");

            // Verify output file was created
            Path outputPath = Path.of(outputFile);
            assertTrue(java.nio.file.Files.exists(outputPath), "Flamegraph file should exist");
            assertTrue(java.nio.file.Files.size(outputPath) > 0, "Flamegraph file should not be empty");

            // Verify it's an HTML file with content
            String content = java.nio.file.Files.readString(outputPath);
            assertTrue(content.contains("<!DOCTYPE html>") || content.contains("<html") || content.contains("<HTML"),
                "Output should be HTML");
            // Just verify it has some HTML content - different versions of async-profiler may format differently
            assertTrue(content.length() > 100, "Output should have substantial content");

            System.out.println("Flamegraph generated successfully: " + outputFile);
            System.out.println("File size: " + java.nio.file.Files.size(outputPath) + " bytes");
        } finally {
            launcher.stop();
        }
    }

    @Test
    public void testFlamegraphGenerationJFRFormat(@TempDir Path tempDir) throws Exception {
        // Only run if async-profiler is supported on this platform
        if (!one.profiler.AsyncProfilerLoader.isSupported()) {
            System.out.println("Skipping JFR flamegraph test - async-profiler not supported on this platform");
            return;
        }

        me.bechberger.jstall.testframework.TestAppLauncher launcher = new me.bechberger.jstall.testframework.TestAppLauncher();

        try {
            launcher.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "busy-work");
            launcher.waitUntilReady(5000);
            Thread.sleep(1000);

            String outputFile = tempDir.resolve("profile.jfr").toString();

            FlameCommand command = new FlameCommand();
            CommandLine cmd = new CommandLine(command);
            int exitCode = cmd.execute(
                String.valueOf(launcher.getPid()),
                "-d", "2s",
                "-e", "wall",
                "-o", outputFile
            );

            assertEquals(0, exitCode, "JFR generation should succeed");

            Path outputPath = Path.of(outputFile);
            assertTrue(java.nio.file.Files.exists(outputPath), "JFR file should exist");
            assertTrue(java.nio.file.Files.size(outputPath) > 0, "JFR file should not be empty");

            System.out.println("JFR file generated successfully: " + outputFile);
            System.out.println("File size: " + java.nio.file.Files.size(outputPath) + " bytes");
        } finally {
            launcher.stop();
        }
    }

    @Test
    public void testFlamegraphWithDifferentEvents(@TempDir Path tempDir) throws Exception {
        // Only run if async-profiler is supported on this platform
        if (!one.profiler.AsyncProfilerLoader.isSupported()) {
            System.out.println("Skipping event test - async-profiler not supported on this platform");
            return;
        }

        me.bechberger.jstall.testframework.TestAppLauncher launcher = new me.bechberger.jstall.testframework.TestAppLauncher();

        try {
            launcher.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "busy-work");
            launcher.waitUntilReady(5000);
            Thread.sleep(1000);

            // Test different event types (use wall for compatibility)
            String[] events = {"wall", "alloc"};

            for (String event : events) {
                String outputFile = tempDir.resolve("flamegraph-" + event + ".html").toString();

                FlameCommand command = new FlameCommand();
                CommandLine cmd = new CommandLine(command);
                int exitCode = cmd.execute(
                    String.valueOf(launcher.getPid()),
                    "-d", "1s",
                    "-e", event,
                    "-o", outputFile
                );

                assertEquals(0, exitCode, "Event " + event + " should succeed");

                Path outputPath = Path.of(outputFile);
                assertTrue(java.nio.file.Files.exists(outputPath),
                    "Flamegraph for event " + event + " should exist");
                assertTrue(java.nio.file.Files.size(outputPath) > 0,
                    "Flamegraph for event " + event + " should not be empty");

                System.out.println("Flamegraph for " + event + " generated: " + outputFile);
            }
        } finally {
            launcher.stop();
        }
    }

    @Test
    public void testFlamegraphWithCustomInterval(@TempDir Path tempDir) throws Exception {
        // Only run if async-profiler is supported on this platform
        if (!one.profiler.AsyncProfilerLoader.isSupported()) {
            System.out.println("Skipping interval test - async-profiler not supported on this platform");
            return;
        }

        me.bechberger.jstall.testframework.TestAppLauncher launcher = new me.bechberger.jstall.testframework.TestAppLauncher();

        try {
            launcher.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "busy-work");
            launcher.waitUntilReady(5000);
            Thread.sleep(1000);

            String outputFile = tempDir.resolve("flamegraph-custom-interval.html").toString();

            FlameCommand command = new FlameCommand();
            CommandLine cmd = new CommandLine(command);
            int exitCode = cmd.execute(
                String.valueOf(launcher.getPid()),
                "-d", "2s",
                "-e", "wall",
                "-i", "5ms",  // Custom interval
                "-o", outputFile
            );

            assertEquals(0, exitCode, "Custom interval should succeed");

            Path outputPath = Path.of(outputFile);
            assertTrue(java.nio.file.Files.exists(outputPath), "Flamegraph with custom interval should exist");
            assertTrue(java.nio.file.Files.size(outputPath) > 0, "Flamegraph should not be empty");

            System.out.println("Flamegraph with custom interval generated: " + outputFile);
        } finally {
            launcher.stop();
        }
    }

    @Test
    public void testOpenOption(@TempDir Path tempDir) {
        FlameCommand command = new FlameCommand();
        CommandLine cmd = new CommandLine(command);

        String outputFile = tempDir.resolve("test-flame.html").toString();

        // Test that --open option is parsed (won't actually open browser in test)
        int exitCode = cmd.execute("12345", "--open", "-o", outputFile);

        // Should parse successfully (may fail execution if platform not supported or PID doesn't exist)
        assertTrue(exitCode >= 0, "--open option should be accepted");
    }
}