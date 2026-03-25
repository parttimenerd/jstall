package me.bechberger.jstall.cli;

// Use the fluent RunResultAssert returned by RunCommandUtil.run
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
        // deadlock analyzer succeeds even if no deadlock is found (exit 0)
        RunCommandUtil.run("-f", recording.toString(), "deadlock", "10000").hasNoError();
    }

    @Test
    void testMostWorkCommandWithReplay() throws Exception {
        Path recording = createStandardRecording();
        RunCommandUtil.run("-f", recording.toString(), "most-work", "10000").hasNoError().output().isNotBlank();
    }

    @Test
    void testWaitingThreadsCommandWithReplay() throws Exception {
        Path recording = createStandardRecording();
        RunCommandUtil.run("-f", recording.toString(), "waiting-threads", "10000").hasNoError().output().isNotBlank();
    }

    @Test
    void testDependencyGraphCommandWithReplay() throws Exception {
        Path recording = createStandardRecording();
        RunResultAssert result = RunCommandUtil.run("-f", recording.toString(), "dependency-graph", "10000");
        // dependency-graph may return non-zero if graph is empty; just verify it doesn't report unknown option
        result.errorOutput().doesNotContain("Unknown option");
    }

    @Test
    void testJvmSupportCommandWithReplay() throws Exception {
        Path recording = createStandardRecording();
        RunCommandUtil.run("-f", recording.toString(), "jvm-support", "10000").hasNoError();
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

        RunCommandUtil.run("-f", tempFile.toString(), "jvm-support", "7777").hasExitCode(10).output().contains("outdated");
    }

    // ================== --file= equals form ==================

    @Test
    void testFileEqualsForm() throws Exception {
        Path recording = createStandardRecording();
        RunCommandUtil.run("--file=" + recording, "status", "10000").hasNoError().output().isNotBlank();
    }

    // ================== implicit status with -f ==================

    @Test
    void testImplicitStatusWithReplayFile() throws Exception {
        Path recording = createStandardRecording();
        // -f file.zip 10000  →  preprocessed to  -f file.zip status 10000
        RunCommandUtil.run("-f", recording.toString(), "10000").hasNoError().output().isNotBlank();
    }

    @Test
    void testImplicitStatusWithEqualsForm() throws Exception {
        Path recording = createStandardRecording();
        RunCommandUtil.run("--file=" + recording, "10000").hasNoError().output().isNotBlank();
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
        RunResultAssert all = RunCommandUtil.run("-f", tempFile.toString(), "list").hasNoError();
        all.output().contains("1000");
        all.output().contains("2000");

        // With filter: only Alpha
        RunResultAssert filtered = RunCommandUtil.run("-f", tempFile.toString(), "list", "Alpha").hasNoError();
        filtered.output().contains("Alpha");
        filtered.output().doesNotContain("Beta");
    }

    @Test
    void testListReplayNoMatch() throws Exception {
        Path recording = createStandardRecording();
        RunCommandUtil.run("-f", recording.toString(), "list", "nonexistent").hasError().errorOutput().contains("No JVMs found");
    }

    // ================== empty ZIP / corrupt data ==================

    @Test
    void testEmptyZipFile(@TempDir Path tempDir) throws Exception {
        Path emptyZip = tempDir.resolve("empty.zip");
        // Create a minimal valid ZIP (empty archive) 
        try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(emptyZip))) {
            // no entries; explicitly finish to ensure central directory is written
            zos.finish();
        }

        RunCommandUtil.run("-f", emptyZip.toString(), "status", "10000").hasError();
    }

    @Test
    void testTruncatedZipFile(@TempDir Path tempDir) throws Exception {
        Path truncated = tempDir.resolve("truncated.zip");
        Files.write(truncated, new byte[]{0x50, 0x4B, 0x03, 0x04}); // ZIP magic header, truncated

        RunCommandUtil.run("-f", truncated.toString(), "status", "10000").hasError();
    }

    // ================== subcommand help texts ==================

    @Test
    void testStatusHelp() {
        RunResultAssert result = RunCommandUtil.run("status", "--help").hasNoError();
        result.output().contains("status");
        result.output().contains("--top");
        result.output().contains("--no-native");
    }

    @Test
    void testThreadsHelp() {
        RunCommandUtil.run("threads", "--help").hasNoError().output().contains("threads");
    }

    @Test
    void testMostWorkHelp() {
        RunCommandUtil.run("most-work", "--help").hasNoError().output().contains("most-work");
    }

    @Test
    void testWaitingThreadsHelp() {
        RunCommandUtil.run("waiting-threads", "--help").hasNoError().output().contains("waiting-threads");
    }

    @Test
    void testDependencyGraphHelp() {
        RunCommandUtil.run("dependency-graph", "--help").hasNoError().output().contains("dependency-graph");
    }

    @Test
    void testJvmSupportHelp() {
        RunCommandUtil.run("jvm-support", "--help").hasNoError().output().contains("jvm-support");
    }

    @Test
    void testListHelp() {
        RunCommandUtil.run("list", "--help").hasNoError().output().contains("list");
    }

    @Test
    void testVmVitalsHelp() {
        RunResultAssert result = RunCommandUtil.run("vm-vitals", "--help").hasNoError();
        result.output().contains("vm-vitals");
        result.output().contains("--top");
    }

    @Test
    void testGcHeapInfoHelp() {
        RunCommandUtil.run("gc-heap-info", "--help").hasNoError().output().contains("gc-heap-info");
    }

    // ================== no-args behaviour ==================

    @Test
    void testNoArgsShowsUsage() {
        // When no args, Main.run() is called which prints usage + JVM list
        RunResultAssert result = RunCommandUtil.run().hasNoError();
        assertTrue(result.get().out().contains("Usage:") || result.get().out().contains("Available commands"),
            () -> "Should show usage. Output: " + result.get().out());
    }

    // ================== replay with --top and --no-native options ==================

    @Test
    void testStatusWithTopOption() throws Exception {
        Path recording = createStandardRecording();
        RunCommandUtil.run("-f", recording.toString(), "status", "--top", "1", "10000").hasNoError().output().isNotBlank();
    }

    @Test
    void testStatusWithNoNativeOption() throws Exception {
        Path recording = createStandardRecording();
        RunCommandUtil.run("-f", recording.toString(), "status", "--no-native", "10000").hasNoError().output().isNotBlank();
    }

    // ================== replay with --dumps and --interval ==================

    @Test
    void testThreadsWithDumpsOption() throws Exception {
        Path recording = createStandardRecording();
        RunCommandUtil.run("-f", recording.toString(), "threads", "--dumps", "1", "10000").hasNoError().output().isNotBlank();
    }

    // ================== status output contains expected sections ==================

    @Test
    void testStatusOutputContainsSections() throws Exception {
        Path recording = createStandardRecording();
        RunResultAssert result = RunCommandUtil.run("-f", recording.toString(), "status", "10000").hasNoError();

        String out = result.get().out();
        // Status runs MostWorkAnalyzer, ThreadsAnalyzer, etc.
        assertTrue(out.contains("==="), "Should contain section headers (=== analyzer_name ===)");
    }
}