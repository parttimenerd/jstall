package me.bechberger.jstall.cli;

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
    void testStatusCommandWithReplayFile() throws Exception {
        Path recordingFile = createTestRecording();

        RunCommandUtil.run("-f", recordingFile.toString(), "status", "10000").hasNoError().output().isNotEmpty();
    }

    @Test
    void testStatusCommandWithReplayFilePathFormatVariation() throws Exception {
        Path recordingFile = createTestRecording();

        // Test --file long form
        RunCommandUtil.run("--file", recordingFile.toString(), "status", "10000").hasNoError();

        // Test -f short form
        RunCommandUtil.run("-f", recordingFile.toString(), "status", "10000").hasNoError();
    }

    @Test
    void testReplayListCommand() throws Exception {
        Path recordingFile = createTestRecording();

        RunResultAssert result = RunCommandUtil.run("--file", recordingFile.toString(), "list").hasNoError();
        assertTrue(result.get().out().contains("10000") || result.get().out().contains("Recorded JVMs"), () ->
            "Output should contain recorded JVM info. Output was: " + result.get().out());
    }

    @Test
    void testStatusWithReplayBeforeCommand() throws Exception {
        Path recordingFile = createTestRecording();

        // Test with replay file before command: -f file.zip status 10000
        RunCommandUtil.run("-f", recordingFile.toString(), "status", "10000").hasNoError().output().isNotEmpty();
    }

    @Test
    void testStatusWithReplayAfterCommand() throws Exception {
        Path recordingFile = createTestRecording();

        // Test with --file long form before command
        RunCommandUtil.run("--file", recordingFile.toString(), "status", "10000").hasNoError().output().isNotEmpty();
    }

    @Test
    void testDefaultStatusCommandWithReplayFile() throws Exception {
        Path recordingFile = createTestRecording();

        // Test default to status: -f file.zip 10000 (without explicit status)
        RunCommandUtil.run("-f", recordingFile.toString(), "10000").hasNoError().output().isNotEmpty();
    }

    @Test
    void testThreadsCommandWithReplay() throws Exception {
        Path recordingFile = createTestRecording();

        RunCommandUtil.run("-f", recordingFile.toString(), "threads", "10000").hasNoError();
    }

    @Test
    void testMissingReplayFile(@TempDir Path tempDir) {
        Path nonExistent = tempDir.resolve("nonexistent.zip");

        RunResultAssert result = RunCommandUtil.run("-f", nonExistent.toString(), "status", "10000").hasError();
        assertTrue(!result.get().err().isEmpty() || result.get().out().contains("Error") ||
            result.get().out().contains("not found"), () -> 
            "Should have error message. stderr: " + result.get().err() + ", stdout: " + result.get().out());
    }

    @Test
    void testMissingPidInReplay() throws Exception {
        Path recordingFile = createTestRecording();

        // Try to analyze a PID that isn't in the recording
        RunResultAssert result = RunCommandUtil.run("-f", recordingFile.toString(), "status", "99999").hasError();
        assertTrue(!result.get().err().isEmpty() || result.get().out().contains("not found") ||
                   result.get().out().contains("No recorded"), () ->
            "Should have appropriate error. stderr: " + result.get().err() + ", stdout: " + result.get().out());
    }

    @Test
    void testReplayFileWithMultipleJvms() throws Exception {
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
        RunCommandUtil.run("-f", recordingFile.toString(), "status", "11111").hasNoError();

        // Can analyze second JVM
        RunCommandUtil.run("-f", recordingFile.toString(), "status", "22222").hasNoError();

        // Cannot analyze non-existent JVM
        RunCommandUtil.run("-f", recordingFile.toString(), "status", "33333").hasError();
    }

    @Test
    void testReplayFileWithAllTarget() throws Exception {
        Path recordingFile = Files.createTempFile("multi-jvm-all-", ".zip");
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

        RunCommandUtil.run("-f", recordingFile.toString(), "status", "all").hasNoError().output().isNotBlank();
    }

    @Test
    void testListReplayedJvms() throws Exception {
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

        RunResultAssert result = RunCommandUtil.run("-f", recordingFile.toString(), "list").hasNoError();

        String output = result.get().out();
        assertTrue(output.contains("5000") || output.contains("ServiceA") || 
            output.contains("Recorded JVMs"), () ->
            "Output should contain recorded JVM info. Output was: " + output);
    }

    @Test
    void testReplayFileInvalidFormat(@TempDir Path tempDir) throws Exception {
        // Create a file that looks like a replay file but isn't
        Path invalidFile = tempDir.resolve("invalid.zip");
        Files.writeString(invalidFile, "This is not a valid ZIP file");

        RunCommandUtil.run("-f", invalidFile.toString(), "status", "10000").hasError();
    }

    @Test
    void testHelpWithReplayFile() {
        RunResultAssert result = RunCommandUtil.run("--help").hasNoError();
        assertTrue(result.get().out().contains("--file") || result.get().out().contains("-f"), () ->
            "Help should document replay file option. Output: " + result.get().out());
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

        RunCommandUtil.run("-f", recordingFile.toString(), "status", "8888").hasNoError();
    }

    @Test
    void testBaseAnalyzerCommandWithReplay() throws Exception {
        Path recordingFile = createTestRecording();

        // Test various analyzer commands with replay
        String[] commands = {"status", "threads"};

        for (String cmd : commands) {
            RunCommandUtil.run("-f", recordingFile.toString(), cmd, "10000").hasNoError();
        }
    }

    @Test
    void testReplayFlagVariations() throws Exception {
        Path recordingFile = createTestRecording();

        // All these variations should work (global -f/--file must come before subcommand)
        RunCommandUtil.run("-f", recordingFile.toString(), "status", "10000").hasNoError();
        RunCommandUtil.run("--file", recordingFile.toString(), "status", "10000").hasNoError();
    }
}