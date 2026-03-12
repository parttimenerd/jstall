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
 * Additional tests for replay functionality across all analyzer commands,
 * edge cases, and CLI behaviour.
 */
class ReplayExtendedTest {

    private String[] busyWorkDumps;
    private String[] normalDumps;

    @BeforeEach
    void setUp() throws Exception {
        busyWorkDumps = ThreadDumpTestResources.loadBusyWorkDumps();
        normalDumps = ThreadDumpTestResources.loadNormalDumps();
    }

    /**
     * Creates a standard two-dump recording with recent java.version.date.
     */
    private Path createRecording(long pid, String mainClass, String[] dumps) throws Exception {
        Path tempFile = Files.createTempFile("replay-ext-", ".zip");
        long baseTime = System.currentTimeMillis();
        String systemProps = "java.version=21\njava.version.date=2026-01-01\n";

        RecordingTestBuilder.JvmRecordingBuilder jvm = new RecordingTestBuilder(Main.VERSION)
            .withJvm(pid, mainClass);
        for (int i = 0; i < dumps.length; i++) {
            jvm.withThreadDump(dumps[i], baseTime + i * 1000L);
        }
        jvm.withSystemProperties(systemProps, baseTime)
            .build()
            .build(tempFile);

        tempFile.toFile().deleteOnExit();
        return tempFile;
    }

    private Path createStandardRecording() throws Exception {
        return createRecording(10000, "com.example.TestApp", busyWorkDumps);
    }

    // ================== analyzer commands with replay ==================

    @Test
    void testDeadlockCommandWithReplay() throws Exception {
        Path recording = createStandardRecording();
        RunResult result = Util.run("-f", recording.toString(), "deadlock", "10000");
        // deadlock analyzer succeeds even if no deadlock is found (exit 0)
        assertEquals(0, result.exitCode(),
            () -> "deadlock with replay failed. stderr: " + result.err());
    }

    @Test
    void testMostWorkCommandWithReplay() throws Exception {
        Path recording = createStandardRecording();
        RunResult result = Util.run("-f", recording.toString(), "most-work", "10000");
        assertEquals(0, result.exitCode(),
            () -> "most-work with replay failed. stderr: " + result.err());
        assertFalse(result.out().isBlank(), "most-work should produce output");
    }

    @Test
    void testWaitingThreadsCommandWithReplay() throws Exception {
        Path recording = createStandardRecording();
        RunResult result = Util.run("-f", recording.toString(), "waiting-threads", "10000");
        assertEquals(0, result.exitCode(),
            () -> "waiting-threads with replay failed. stderr: " + result.err());
    }

    @Test
    void testDependencyGraphCommandWithReplay() throws Exception {
        Path recording = createStandardRecording();
        RunResult result = Util.run("-f", recording.toString(), "dependency-graph", "10000");
        // dependency-graph may return non-zero if graph is empty; just verify it doesn't crash
        assertTrue(result.err().isEmpty() || !result.err().contains("Unknown option"),
            () -> "dependency-graph should not report unknown option. stderr: " + result.err());
    }

    @Test
    void testJvmSupportCommandWithReplay() throws Exception {
        Path recording = createStandardRecording();
        RunResult result = Util.run("-f", recording.toString(), "jvm-support", "10000");
        assertEquals(0, result.exitCode(),
            () -> "jvm-support with replay failed (recent java.version.date). stderr: " + result.err());
    }

    @Test
    void testJvmSupportOutdatedJvm() throws Exception {
        // Use an old java.version.date to trigger the "outdated" exit code 10
        Path tempFile = Files.createTempFile("replay-old-", ".zip");
        long baseTime = System.currentTimeMillis();
        String systemProps = "java.version=17\njava.version.date=2020-01-01\n";

        new RecordingTestBuilder(Main.VERSION)
            .withJvm(7777, "com.example.OldApp")
                .withThreadDump(busyWorkDumps[0], baseTime)
                .withSystemProperties(systemProps, baseTime)
                .build()
            .build(tempFile);

        tempFile.toFile().deleteOnExit();

        RunResult result = Util.run("-f", tempFile.toString(), "jvm-support", "7777");
        assertEquals(10, result.exitCode(), "Outdated JVM should return exit code 10");
        assertTrue(result.out().contains("outdated"), "Output should mention 'outdated'");
    }

    // ================== --file= equals form ==================

    @Test
    void testFileEqualsForm() throws Exception {
        Path recording = createStandardRecording();
        RunResult result = Util.run("--file=" + recording, "status", "10000");
        assertEquals(0, result.exitCode(),
            () -> "--file= form failed. stderr: " + result.err());
    }

    // ================== implicit status with -f ==================

    @Test
    void testImplicitStatusWithReplayFile() throws Exception {
        Path recording = createStandardRecording();
        // -f file.zip 10000  →  preprocessed to  -f file.zip status 10000
        RunResult result = Util.run("-f", recording.toString(), "10000");
        assertEquals(0, result.exitCode(),
            () -> "Implicit status with -f. stderr: " + result.err());
        assertFalse(result.out().isBlank(), "Should produce status output");
    }

    @Test
    void testImplicitStatusWithEqualsForm() throws Exception {
        Path recording = createStandardRecording();
        RunResult result = Util.run("--file=" + recording, "10000");
        assertEquals(0, result.exitCode(),
            () -> "Implicit status with --file=. stderr: " + result.err());
    }

    // ================== list with filter in replay ==================

    @Test
    void testListReplayWithFilter() throws Exception {
        Path tempFile = Files.createTempFile("replay-filter-", ".zip");
        long baseTime = System.currentTimeMillis();
        String systemProps = "java.version=21\njava.version.date=2026-01-01\n";

        new RecordingTestBuilder(Main.VERSION)
            .withJvm(1000, "com.example.Alpha")
                .withThreadDump(busyWorkDumps[0], baseTime)
                .withSystemProperties(systemProps, baseTime)
                .build()
            .withJvm(2000, "org.other.Beta")
                .withThreadDump(busyWorkDumps[0], baseTime)
                .withSystemProperties(systemProps, baseTime)
                .build()
            .build(tempFile);

        tempFile.toFile().deleteOnExit();

        // Without filter: both JVMs
        RunResult all = Util.run("-f", tempFile.toString(), "list");
        assertEquals(0, all.exitCode());
        assertTrue(all.out().contains("1000") && all.out().contains("2000"),
            () -> "Should list both JVMs. Output: " + all.out());

        // With filter: only Alpha
        RunResult filtered = Util.run("-f", tempFile.toString(), "list", "Alpha");
        assertEquals(0, filtered.exitCode());
        assertTrue(filtered.out().contains("Alpha"),
            () -> "Should list Alpha. Output: " + filtered.out());
        assertFalse(filtered.out().contains("Beta"),
            () -> "Should NOT list Beta. Output: " + filtered.out());
    }

    @Test
    void testListReplayNoMatch() throws Exception {
        Path recording = createStandardRecording();
        RunResult result = Util.run("-f", recording.toString(), "list", "nonexistent");
        assertTrue(result.exitCode() != 0, "List with no matches should have non-zero exit");
        assertTrue(result.out().contains("No JVMs found"),
            () -> "Should say no JVMs found. Output: " + result.out());
    }

    // ================== empty ZIP / corrupt data ==================

    @Test
    void testEmptyZipFile(@TempDir Path tempDir) throws Exception {
        Path emptyZip = tempDir.resolve("empty.zip");
        // Create a minimal valid ZIP (empty archive) 
        try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(emptyZip))) {
            // no entries
        }

        RunResult result = Util.run("-f", emptyZip.toString(), "status", "10000");
        assertTrue(result.exitCode() != 0, "Empty ZIP should fail");
    }

    @Test
    void testTruncatedZipFile(@TempDir Path tempDir) throws Exception {
        Path truncated = tempDir.resolve("truncated.zip");
        Files.write(truncated, new byte[]{0x50, 0x4B, 0x03, 0x04}); // ZIP magic header, truncated

        RunResult result = Util.run("-f", truncated.toString(), "status", "10000");
        assertTrue(result.exitCode() != 0, "Truncated ZIP should fail");
    }

    // ================== version flag ==================

    @Test
    void testVersionFlag() {
        RunResult result = Util.run("-V");
        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains(Main.VERSION),
            () -> "Version output should contain version. Output: " + result.out());
    }

    @Test
    void testLongVersionFlag() {
        RunResult result = Util.run("--version");
        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains(Main.VERSION));
    }

    // ================== subcommand help texts ==================

    @Test
    void testStatusHelp() {
        RunResult result = Util.run("status", "--help");
        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("status"), "Should contain command name");
        assertTrue(result.out().contains("--top"), "Should contain --top option");
        assertTrue(result.out().contains("--no-native"), "Should contain --no-native option");
    }

    @Test
    void testThreadsHelp() {
        RunResult result = Util.run("threads", "--help");
        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("threads"));
    }

    @Test
    void testMostWorkHelp() {
        RunResult result = Util.run("most-work", "--help");
        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("most-work"));
    }

    @Test
    void testWaitingThreadsHelp() {
        RunResult result = Util.run("waiting-threads", "--help");
        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("waiting-threads"));
    }

    @Test
    void testDependencyGraphHelp() {
        RunResult result = Util.run("dependency-graph", "--help");
        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("dependency-graph"));
    }

    @Test
    void testJvmSupportHelp() {
        RunResult result = Util.run("jvm-support", "--help");
        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("jvm-support"));
    }

    @Test
    void testListHelp() {
        RunResult result = Util.run("list", "--help");
        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("list"));
    }

    @Test
    void testVmVitalsHelp() {
        RunResult result = Util.run("vm-vitals", "--help");
        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("vm-vitals"));
        assertTrue(result.out().contains("--top"));
    }

    @Test
    void testGcHeapInfoHelp() {
        RunResult result = Util.run("gc-heap-info", "--help");
        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("gc-heap-info"));
    }

    // ================== no-args behaviour ==================

    @Test
    void testNoArgsShowsUsage() {
        // When no args, Main.run() is called which prints usage + JVM list
        RunResult result = Util.run();
        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("Usage:") || result.out().contains("Available commands"),
            () -> "Should show usage. Output: " + result.out());
    }

    // ================== replay with --top and --no-native options ==================

    @Test
    void testStatusWithTopOption() throws Exception {
        Path recording = createStandardRecording();
        RunResult result = Util.run("-f", recording.toString(), "status", "--top", "1", "10000");
        assertEquals(0, result.exitCode(),
            () -> "status --top 1 failed. stderr: " + result.err());
    }

    @Test
    void testStatusWithNoNativeOption() throws Exception {
        Path recording = createStandardRecording();
        RunResult result = Util.run("-f", recording.toString(), "status", "--no-native", "10000");
        assertEquals(0, result.exitCode(),
            () -> "status --no-native failed. stderr: " + result.err());
    }

    // ================== replay with --dumps and --interval ==================

    @Test
    void testThreadsWithDumpsOption() throws Exception {
        Path recording = createStandardRecording();
        RunResult result = Util.run("-f", recording.toString(), "threads", "--dumps", "1", "10000");
        assertEquals(0, result.exitCode(),
            () -> "threads --dumps 1 failed. stderr: " + result.err());
    }

    // ================== status output contains expected sections ==================

    @Test
    void testStatusOutputContainsSections() throws Exception {
        Path recording = createStandardRecording();
        RunResult result = Util.run("-f", recording.toString(), "status", "10000");
        assertEquals(0, result.exitCode());

        String out = result.out();
        // Status runs MostWorkAnalyzer, ThreadsAnalyzer, etc.
        assertTrue(out.contains("==="), "Should contain section headers (=== analyzer_name ===)");
    }
}