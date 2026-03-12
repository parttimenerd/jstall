package me.bechberger.jstall.cli;

import me.bechberger.femtocli.RunResult;
import me.bechberger.jstall.Main;
import me.bechberger.jstall.provider.RecordingTestBuilder;
import me.bechberger.jstall.provider.ThreadDumpTestResources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the record/replay CLI commands and replay flag (-f/--file).
 */
class RecordReplayCommandTest {

    private String[] busyWorkDumps;
    private String[] normalDumps;

    @BeforeEach
    void setUp() throws Exception {
        busyWorkDumps = ThreadDumpTestResources.loadBusyWorkDumps();
        normalDumps = ThreadDumpTestResources.loadNormalDumps();
    }

    private Path createTestRecording() throws Exception {
        Path tempFile = Files.createTempFile("cli-record-", ".zip");
        long baseTime = System.currentTimeMillis();
        String systemProps = "java.version=21\njava.version.date=2026-01-01\n";

        new RecordingTestBuilder(Main.VERSION)
            .withJvm(10000, "com.example.TestApp")
                .withThreadDump(busyWorkDumps[0], baseTime)
                .withThreadDump(busyWorkDumps[1], baseTime + 1000)
                .withSystemProperties(systemProps, baseTime)
                .build()
            .build(tempFile);

        tempFile.toFile().deleteOnExit();
        return tempFile;
    }

    @Test
    void testStatusCommandWithReplayFile(@TempDir Path tempDir) throws Exception {
        Path recordingFile = createTestRecording();

        RunResult result = Util.run("-f", recordingFile.toString(), "status", "10000");

        assertFalse(result.exitCode() != 0, () -> 
            "Command should succeed. stderr: " + result.err() + ", stdout: " + result.out());
        assertFalse(result.out().isEmpty(), "Should produce analysis output");
    }

    @Test
    void testStatusCommandWithReplayFilePathFormatVariation(@TempDir Path tempDir) throws Exception {
        Path recordingFile = createTestRecording();

        // Test --file long form
        RunResult result1 = Util.run("--file", recordingFile.toString(), "status", "10000");
        assertFalse(result1.exitCode() != 0, () -> 
            "Command with --file should succeed. stderr: " + result1.err());

        // Test -f short form
        RunResult result2 = Util.run("-f", recordingFile.toString(), "status", "10000");
        assertFalse(result2.exitCode() != 0, () -> 
            "Command with -f should succeed. stderr: " + result2.err());
    }

    @Test
    void testReplayListCommand(@TempDir Path tempDir) throws Exception {
        Path recordingFile = createTestRecording();

        RunResult result = Util.run("--file", recordingFile.toString(), "list");

        assertFalse(result.exitCode() != 0, () -> 
            "List command should succeed. stderr: " + result.err());
        assertTrue(result.out().contains("10000") || result.out().contains("Recorded JVMs"), () ->
            "Output should contain recorded JVM info. Output was: " + result.out());
    }

    @Test
    void testStatusWithReplayBeforeCommand(@TempDir Path tempDir) throws Exception {
        Path recordingFile = createTestRecording();

        // Test with replay file before command: -f file.zip status 10000
        RunResult result = Util.run("-f", recordingFile.toString(), "status", "10000");

        assertFalse(result.exitCode() != 0, () -> 
            "Command should succeed with replay file before command. stderr: " + result.err());
        assertFalse(result.out().isEmpty(), "Should produce output");
    }

    @Test
    void testStatusWithReplayAfterCommand(@TempDir Path tempDir) throws Exception {
        Path recordingFile = createTestRecording();

        // Test with --file long form before command
        RunResult result = Util.run("--file", recordingFile.toString(), "status", "10000");

        assertFalse(result.exitCode() != 0, () -> 
            "Command should succeed with replay file before command. stderr: " + result.err());
        assertFalse(result.out().isEmpty(), "Should produce output");
    }

    @Test
    void testDefaultStatusCommandWithReplayFile(@TempDir Path tempDir) throws Exception {
        Path recordingFile = createTestRecording();

        // Test default to status: -f file.zip 10000 (without explicit status)
        RunResult result = Util.run("-f", recordingFile.toString(), "10000");

        assertFalse(result.exitCode() != 0, () -> 
            "Command should default to status. stderr: " + result.err());
        assertFalse(result.out().isEmpty(), "Should produce status output");
    }

    @Test
    void testThreadsCommandWithReplay(@TempDir Path tempDir) throws Exception {
        Path recordingFile = createTestRecording();

        RunResult result = Util.run("-f", recordingFile.toString(), "threads", "10000");

        assertFalse(result.exitCode() != 0, () -> 
            "Threads command should succeed. stderr: " + result.err());
    }

    @Test
    void testMissingReplayFile(@TempDir Path tempDir) throws Exception {
        Path nonExistent = tempDir.resolve("nonexistent.zip");

        RunResult result = Util.run("-f", nonExistent.toString(), "status", "10000");

        assertTrue(result.exitCode() != 0, "Should fail for non-existent replay file");
        assertTrue(result.err().length() > 0 || result.out().contains("Error") ||
            result.out().contains("not found"), () -> 
            "Should have error message. stderr: " + result.err() + ", stdout: " + result.out());
    }

    @Test
    void testMissingPidInReplay(@TempDir Path tempDir) throws Exception {
        Path recordingFile = createTestRecording();

        // Try to analyze a PID that isn't in the recording
        RunResult result = Util.run("-f", recordingFile.toString(), "status", "99999");

        assertTrue(result.exitCode() != 0, "Should fail for missing PID");
        assertTrue(!result.err().isEmpty() || result.out().contains("not found") ||
                   result.out().contains("No recorded"), () ->
            "Should have appropriate error. stderr: " + result.err() + ", stdout: " + result.out());
    }

    @Test
    void testReplayFileWithMultipleJvms(@TempDir Path tempDir) throws Exception {
        Path recordingFile = Files.createTempFile("multi-jvm-", ".zip");
        long baseTime = System.currentTimeMillis();
        String systemProps = "java.version=21\njava.version.date=2026-01-01\n";

        new RecordingTestBuilder(Main.VERSION)
            .withJvm(11111, "com.example.App1")
                .withThreadDump(busyWorkDumps[0], baseTime)
                .withThreadDump(busyWorkDumps[1], baseTime + 1000)
                .withSystemProperties(systemProps, baseTime)
                .build()
            .withJvm(22222, "com.example.App2")
                .withThreadDump(normalDumps[0], baseTime)
                .withThreadDump(normalDumps[0], baseTime + 1000)
                .withSystemProperties(systemProps, baseTime)
                .build()
            .build(recordingFile);

        recordingFile.toFile().deleteOnExit();

        // Can analyze first JVM
        RunResult result1 = Util.run("-f", recordingFile.toString(), "status", "11111");
        assertFalse(result1.exitCode() != 0, "Should succeed for first PID");

        // Can analyze second JVM
        RunResult result2 = Util.run("-f", recordingFile.toString(), "status", "22222");
        assertFalse(result2.exitCode() != 0, "Should succeed for second PID");

        // Cannot analyze non-existent JVM
        RunResult result3 = Util.run("-f", recordingFile.toString(), "status", "33333");
        assertTrue(result3.exitCode() != 0, "Should fail for non-existent PID");
    }

    @Test
    void testListReplayedJvms(@TempDir Path tempDir) throws Exception {
        Path recordingFile = Files.createTempFile("list-", ".zip");
        long baseTime = System.currentTimeMillis();
        String systemProps = "java.version=21\njava.version.date=2026-01-01\n";

        new RecordingTestBuilder(Main.VERSION)
            .withJvm(5000, "com.example.ServiceA")
                .withThreadDump(busyWorkDumps[0], baseTime)
                .withSystemProperties(systemProps, baseTime)
                .build()
            .withJvm(6000, "com.example.ServiceB")
                .withThreadDump(busyWorkDumps[0], baseTime)
                .withSystemProperties(systemProps, baseTime)
                .build()
            .build(recordingFile);

        recordingFile.toFile().deleteOnExit();

        RunResult result = Util.run("-f", recordingFile.toString(), "list");

        assertFalse(result.exitCode() != 0, () -> 
            "List command should succeed. stderr: " + result.err());
        
        String output = result.out();
        assertTrue(output.contains("5000") || output.contains("ServiceA") || 
            output.contains("Recorded JVMs"), () ->
            "Output should contain recorded JVM info. Output was: " + output);
    }

    @Test
    void testReplayFileInvalidFormat(@TempDir Path tempDir) throws Exception {
        // Create a file that looks like a replay file but isn't
        Path invalidFile = tempDir.resolve("invalid.zip");
        Files.writeString(invalidFile, "This is not a valid ZIP file");

        RunResult result = Util.run("-f", invalidFile.toString(), "status", "10000");

        assertTrue(result.exitCode() != 0, "Should fail for invalid ZIP file");
    }

    @Test
    void testHelpWithReplayFile(@TempDir Path tempDir) throws Exception {
        RunResult result = Util.run("--help");

        assertFalse(result.exitCode() != 0, "Help should succeed");
        assertTrue(result.out().contains("--file") || result.out().contains("-f"), () ->
            "Help should document replay file option. Output: " + result.out());
    }

    @Test
    void testReplayFilePathWithSpaces(@TempDir Path tempDir) throws Exception {
        Path spaceDir = tempDir.resolve("dir with spaces");
        Files.createDirectories(spaceDir);
        Path recordingFile = spaceDir.resolve("recording file.zip");

        long baseTime = System.currentTimeMillis();
        String systemProps = "java.version=21\njava.version.date=2026-01-01\n";

        new RecordingTestBuilder(Main.VERSION)
            .withJvm(8888, "com.example.App")
                .withThreadDump(busyWorkDumps[0], baseTime)
                .withThreadDump(busyWorkDumps[0], baseTime + 1000)
                .withSystemProperties(systemProps, baseTime)
                .build()
            .build(recordingFile);

        RunResult result = Util.run("-f", recordingFile.toString(), "status", "8888");

        assertFalse(result.exitCode() != 0, () -> 
            "Should handle file paths with spaces. stderr: " + result.err());
    }

    @Test
    void testBaseAnalyzerCommandWithReplay(@TempDir Path tempDir) throws Exception {
        Path recordingFile = createTestRecording();

        // Test various analyzer commands with replay
        String[] commands = {"status", "threads"};

        for (String cmd : commands) {
            RunResult result = Util.run("-f", recordingFile.toString(), cmd, "10000");
            assertFalse(result.exitCode() != 0, () -> 
                "Command '" + cmd + "' should work with replay. stderr: " + result.err());
        }
    }

    @Test
    void testReplayFlagVariations(@TempDir Path tempDir) throws Exception {
        Path recordingFile = createTestRecording();

        // All these variations should work (global -f/--file must come before subcommand)
        RunResult r1 = Util.run("-f", recordingFile.toString(), "status", "10000");
        RunResult r2 = Util.run("--file", recordingFile.toString(), "status", "10000");

        assertFalse(r1.exitCode() != 0, () -> "-f should work. stderr: " + r1.err());
        assertFalse(r2.exitCode() != 0, () -> "--file should work. stderr: " + r2.err());
    }
}