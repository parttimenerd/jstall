package me.bechberger.jstall.cli;

import me.bechberger.femtocli.RunResult;
import me.bechberger.jstall.Main;
import me.bechberger.jstall.provider.RecordingTestBuilder;
import me.bechberger.jstall.provider.ThreadDumpTestResources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge-case and complex-interaction tests for the jstall CLI.
 * Covers:
 * - defaultSubcommand behavior (bare PID routes to status)
 * - Replay with deadlock / waiting-threads / dependency-graph / jvm-support / most-work
 * - Multi-PID analysis from a single replay file
 * - Filter-based target resolution in replay mode
 * - Recording with system environment data
 * - Recording with failed JVM entry
 * - Recording with zero thread dumps
 * - Analyzer options (--top, --no-native, --stack-depth, --intelligent-filter, --keep, --dumps)
 * - Empty recording file
 * - List command with filters in replay mode
 * - Cross-analyzer replays (same recording, multiple analyzer commands)
 */
class EdgeCaseAndComplexTest {

    private static final String SYS_PROPS = "java.version=21\njava.version.date=2026-01-01\n";
    private static final String SYS_ENV = "{\"HOME\":\"/home/test\",\"PATH\":\"/usr/bin\"}";

    private String[] busyWorkDumps;
    private String[] normalDumps;
    private String deadlockDump;

    @BeforeEach
    void setUp() throws Exception {
        busyWorkDumps = ThreadDumpTestResources.loadBusyWorkDumps();
        normalDumps = ThreadDumpTestResources.loadNormalDumps();
        deadlockDump = ThreadDumpTestResources.loadDeadlockDump();
    }

    // ---- helper: standard multi-JVM recording with all data types ----

    private Path createRichRecording() throws Exception {
        Path file = Files.createTempFile("rich-", ".zip");
        long t = System.currentTimeMillis();

        new RecordingTestBuilder(Main.VERSION)
            .withJvm(1000, "com.example.BusyApp")
                .withThreadDump(busyWorkDumps[0], t)
                .withThreadDump(busyWorkDumps[1], t + 1000)
                .withThreadDump(busyWorkDumps[2], t + 2000)
                .withSystemProperties(SYS_PROPS, t)
                .withSystemEnvironment(SYS_ENV, t)
                .build()
            .withJvm(2000, "com.example.NormalApp")
                .withThreadDump(normalDumps[0], t)
                .withThreadDump(normalDumps[1], t + 1000)
                .withSystemProperties(SYS_PROPS, t)
                .build()
            .withJvm(3000, "com.example.DeadlockApp")
                .withThreadDump(deadlockDump, t)
                .withThreadDump(deadlockDump, t + 1000)
                .withSystemProperties(SYS_PROPS, t)
                .build()
            .build(file);

        file.toFile().deleteOnExit();
        return file;
    }

    private Path createMinimalRecording(long pid, String mainClass) throws Exception {
        Path file = Files.createTempFile("minimal-", ".zip");
        long t = System.currentTimeMillis();

        new RecordingTestBuilder(Main.VERSION)
            .withJvm(pid, mainClass)
                .withThreadDump(busyWorkDumps[0], t)
                .withThreadDump(busyWorkDumps[1], t + 1000)
                .withSystemProperties(SYS_PROPS, t)
                .build()
            .build(file);

        file.toFile().deleteOnExit();
        return file;
    }

    // ================== deadlock with replay ==================

    @Test
    void deadlockCommandWithDeadlockRecording() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), "deadlock", "3000");
        // DeadLockAnalyzer returns exit code 2 when a deadlock is detected
        assertEquals(2, result.exitCode(),
            () -> "deadlock should return exit 2 for detected deadlock. stderr: " + result.err());
        // Deadlock analyzer should detect the deadlock in the dump
        assertTrue(result.out().toLowerCase().contains("deadlock") || result.out().contains("==="),
            () -> "Should contain deadlock information. out: " + result.out());
    }

    @Test
    void deadlockCommandOnNonDeadlockDump() throws Exception {
        Path file = createRichRecording();

        // PID 1000 has busy-work dumps (no deadlock)
        RunResult result = Util.run("-f", file.toString(), "deadlock", "1000");
        assertEquals(0, result.exitCode(),
            () -> "deadlock on non-deadlock dump should succeed (just find nothing). stderr: " + result.err());
    }

    // ================== waiting-threads with replay ==================

    @Test
    void waitingThreadsWithReplay() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), "waiting-threads", "1000");
        assertEquals(0, result.exitCode(),
            () -> "waiting-threads failed. stderr: " + result.err());
    }

    @Test
    void waitingThreadsWithNoNativeAndStackDepth() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), "waiting-threads",
            "--no-native", "--stack-depth", "5", "1000");
        assertEquals(0, result.exitCode(),
            () -> "waiting-threads --no-native --stack-depth 5 failed. stderr: " + result.err());
    }

    // ================== dependency-graph with replay ==================

    @Test
    void dependencyGraphWithReplay() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), "dependency-graph", "3000");
        assertEquals(0, result.exitCode(),
            () -> "dependency-graph failed. stderr: " + result.err());
    }

    // ================== jvm-support with replay ==================

    @Test
    void jvmSupportWithFutureVersionDate() throws Exception {
        Path file = createRichRecording();

        // java.version.date=2026-01-01 is in the future → JVM is still supported → exit 0
        RunResult result = Util.run("-f", file.toString(), "jvm-support", "1000");
        assertEquals(0, result.exitCode(),
            () -> "jvm-support should detect JVM as supported. stderr: " + result.err());
    }

    @Test
    void jvmSupportWithOutdatedVersionDate() throws Exception {
        Path file = Files.createTempFile("outdated-", ".zip");
        long t = System.currentTimeMillis();
        String oldProps = "java.version=17\njava.version.date=2022-01-01\n";

        new RecordingTestBuilder(Main.VERSION)
            .withJvm(7777, "com.example.OldApp")
                .withThreadDump(busyWorkDumps[0], t)
                .withThreadDump(busyWorkDumps[1], t + 1000)
                .withSystemProperties(oldProps, t)
                .build()
            .build(file);
        file.toFile().deleteOnExit();

        RunResult result = Util.run("-f", file.toString(), "jvm-support", "7777");
        // JvmSupportAnalyzer.OUTDATED_EXIT_CODE = 10
        assertEquals(10, result.exitCode(),
            () -> "jvm-support should report outdated JVM (exit 10). stderr: " + result.err());
    }

    // ================== most-work with options ==================

    @Test
    void mostWorkWithTopOption() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), "most-work", "--top", "5", "1000");
        assertEquals(0, result.exitCode(),
            () -> "most-work --top 5 failed. stderr: " + result.err());
    }

    @Test
    void mostWorkWithStackDepthZero() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), "most-work", "--stack-depth", "0", "1000");
        assertEquals(0, result.exitCode(),
            () -> "most-work --stack-depth 0 (all) failed. stderr: " + result.err());
    }

    @Test
    void mostWorkWithNoNative() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), "most-work", "--no-native", "1000");
        assertEquals(0, result.exitCode(),
            () -> "most-work --no-native failed. stderr: " + result.err());
    }

    // ================== multi-PID analysis in one invocation ==================

    @Test
    void threadsTwoRecordedPids() throws Exception {
        Path file = createRichRecording();

        // Analyze two JVMs in one call
        RunResult result = Util.run("-f", file.toString(), "threads", "1000", "2000");
        assertEquals(0, result.exitCode(),
            () -> "threads for 2 PIDs failed. stderr: " + result.err());
        // Output should contain headers for both analyses
        assertTrue(result.out().contains("1000") || result.out().contains("BusyApp"),
            () -> "Output should reference first PID. out: " + result.out());
    }

    @Test
    void statusMultiplePids() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), "status", "1000", "2000", "3000");
        // PID 3000 has a deadlock → status uses DeadLockAnalyzer → max exit code = 2
        assertTrue(result.exitCode() == 0 || result.exitCode() == 2,
            () -> "status for 3 PIDs should succeed or signal deadlock (exit 2). exit: " + result.exitCode() + ", stderr: " + result.err());
    }

    // ================== filter-based target resolution in replay ==================

    @Test
    void resolveByMainClassFilter() throws Exception {
        Path file = createRichRecording();

        // "Busy" should match "com.example.BusyApp"
        RunResult result = Util.run("-f", file.toString(), "threads", "Busy");
        assertEquals(0, result.exitCode(),
            () -> "Filter 'Busy' should resolve to BusyApp. stderr: " + result.err());
    }

    @Test
    void resolveByMainClassFilterCaseInsensitive() throws Exception {
        Path file = createRichRecording();

        // "normal" (lowercase) should match "com.example.NormalApp"
        RunResult result = Util.run("-f", file.toString(), "threads", "normal");
        assertEquals(0, result.exitCode(),
            () -> "Filter 'normal' should resolve. stderr: " + result.err());
    }

    @Test
    void filterMatchesMultipleJvms() throws Exception {
        Path file = createRichRecording();

        // "example" matches all 3 JVMs
        RunResult result = Util.run("-f", file.toString(), "threads", "example");
        assertEquals(0, result.exitCode(),
            () -> "Filter 'example' should match all JVMs and succeed. stderr: " + result.err());
    }

    @Test
    void filterMatchesNothingInReplay() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), "threads", "NonExistentApp");
        assertTrue(result.exitCode() != 0,
            "Filter that matches nothing should fail");
    }

    // ================== list command with filter in replay ==================

    @Test
    void listWithFilterInReplay() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), "list", "Busy");
        assertEquals(0, result.exitCode(),
            () -> "list with filter 'Busy' failed. stderr: " + result.err());
        assertTrue(result.out().contains("BusyApp") || result.out().contains("1000"),
            () -> "Should list the matching JVM. out: " + result.out());
    }

    @Test
    void listWithNoMatchFilterInReplay() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), "list", "ZzzzNotFound");
        // ListCommand returns 1 when no JVMs match
        assertEquals(1, result.exitCode());
        assertTrue(result.out().contains("No") || result.out().contains("not found") || result.out().contains("no"),
            () -> "Should report no matching JVMs. out: " + result.out());
    }

    @Test
    void listAllInReplayShowsAllJvms() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), "list");
        assertEquals(0, result.exitCode(),
            () -> "list all failed. stderr: " + result.err());
        String out = result.out();
        // Should list all 3 JVMs
        assertTrue(out.contains("1000") || out.contains("BusyApp"),
            () -> "Should contain BusyApp. out: " + out);
        assertTrue(out.contains("2000") || out.contains("NormalApp"),
            () -> "Should contain NormalApp. out: " + out);
        assertTrue(out.contains("3000") || out.contains("DeadlockApp"),
            () -> "Should contain DeadlockApp. out: " + out);
    }

    // ================== recording with failed JVM ==================

    @Test
    void failedJvmInRecordingStillListable() throws Exception {
        Path file = Files.createTempFile("failed-jvm-", ".zip");
        long t = System.currentTimeMillis();

        new RecordingTestBuilder(Main.VERSION)
            .withJvm(4000, "com.example.FailedApp")
                .withThreadDump(busyWorkDumps[0], t)
                .withSystemProperties(SYS_PROPS, t)
                .failed()
                .build()
            .withJvm(5000, "com.example.SuccessApp")
                .withThreadDump(busyWorkDumps[0], t)
                .withThreadDump(busyWorkDumps[1], t + 1000)
                .withSystemProperties(SYS_PROPS, t)
                .build()
            .build(file);
        file.toFile().deleteOnExit();

        // List should show both JVMs
        RunResult listResult = Util.run("-f", file.toString(), "list");
        assertEquals(0, listResult.exitCode(),
            () -> "list failed. stderr: " + listResult.err());

        // Success JVM should still be analyzable
        RunResult statusResult = Util.run("-f", file.toString(), "status", "5000");
        assertEquals(0, statusResult.exitCode(),
            () -> "status for success JVM failed. stderr: " + statusResult.err());
    }

    // ================== recording with zero thread dumps ==================

    @Test
    void jvmWithZeroDumpsFailsGracefully() throws Exception {
        Path file = Files.createTempFile("zero-dumps-", ".zip");
        long t = System.currentTimeMillis();

        new RecordingTestBuilder(Main.VERSION)
            .withJvm(6000, "com.example.NoDumpsApp")
                .withSystemProperties(SYS_PROPS, t)
                .build()
            .build(file);
        file.toFile().deleteOnExit();

        RunResult result = Util.run("-f", file.toString(), "threads", "6000");
        // Should fail (IOException: No thread dumps found)
        assertTrue(result.exitCode() != 0,
            "Should fail for JVM with no thread dumps");
    }

    // ================== empty recording ==================

    @Test
    void emptyRecordingNoJvms() throws Exception {
        Path file = Files.createTempFile("empty-", ".zip");

        new RecordingTestBuilder(Main.VERSION)
            .build(file);
        file.toFile().deleteOnExit();

        RunResult listResult = Util.run("-f", file.toString(), "list");
        // No JVMs → ListCommand returns 1
        assertEquals(1, listResult.exitCode());
    }

    // ================== system environment data in recording ==================

    @Test
    void recordingWithSystemEnvironment() throws Exception {
        Path file = createRichRecording(); // BusyApp has system environment

        // status should still work when system env is present
        RunResult result = Util.run("-f", file.toString(), "status", "1000");
        assertEquals(0, result.exitCode(),
            () -> "status with system env data failed. stderr: " + result.err());
    }

    // ================== --intelligent-filter option ==================

    @Test
    void statusWithIntelligentFilter() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), "status", "--intelligent-filter", "1000");
        assertEquals(0, result.exitCode(),
            () -> "status --intelligent-filter failed. stderr: " + result.err());
    }

    @Test
    void threadsWithIntelligentFilter() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), "threads", "--intelligent-filter", "1000");
        assertEquals(0, result.exitCode(),
            () -> "threads --intelligent-filter failed. stderr: " + result.err());
    }

    // ================== --keep option (in replay, this should be harmless) ==================

    @Test
    void statusWithKeepFlagInReplayMode() throws Exception {
        Path file = createRichRecording();

        // --keep persists dumps to disk; in replay mode, dumps come from ZIP so this is a no-op
        RunResult result = Util.run("-f", file.toString(), "status", "--keep", "1000");
        assertEquals(0, result.exitCode(),
            () -> "status --keep in replay failed. stderr: " + result.err());
    }

    // ================== --dumps option to limit dump count ==================

    @Test
    void statusWithDumpsLimiter() throws Exception {
        Path file = createRichRecording(); // BusyApp has 3 dumps

        RunResult result = Util.run("-f", file.toString(), "status", "--dumps", "2", "1000");
        assertEquals(0, result.exitCode(),
            () -> "status --dumps 2 failed. stderr: " + result.err());
    }

    @Test
    void threadsWithDumpsLimiter() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), "threads", "--dumps", "1", "1000");
        assertEquals(0, result.exitCode(),
            () -> "threads --dumps 1 failed. stderr: " + result.err());
    }

    // ================== cross-analyzer verification: same recording, many commands ==================

    @ParameterizedTest
    @ValueSource(strings = {"status", "threads", "deadlock", "most-work", "waiting-threads",
        "dependency-graph", "jvm-support"})
    void allAnalyzerCommandsOnBusyAppRecording(String command) throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), command, "1000");
        assertEquals(0, result.exitCode(),
            () -> "'" + command + "' should succeed for BusyApp. stderr: " + result.err());
        assertFalse(result.out().isEmpty(),
            () -> "'" + command + "' should produce output");
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "threads", "most-work", "waiting-threads",
        "dependency-graph", "jvm-support"})
    void allNonDeadlockAnalyzerCommandsOnDeadlockAppRecording(String command) throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), command, "3000");
        // status may return 2 because it includes DeadLockAnalyzer
        assertTrue(result.exitCode() == 0 || result.exitCode() == 2,
            () -> "'" + command + "' on deadlock dump returned unexpected exit code " + result.exitCode() + ". stderr: " + result.err());
    }

    @Test
    void deadlockCommandOnDeadlockAppRecordingReturnsTwo() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), "deadlock", "3000");
        assertEquals(2, result.exitCode(),
            () -> "deadlock command on deadlock dump should return exit 2. stderr: " + result.err());
    }

    // ================== threads options combinations ==================

    @Test
    void threadsWithAllOptions() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), "threads",
            "--no-native", "--dumps", "2", "--intelligent-filter", "1000");
        assertEquals(0, result.exitCode(),
            () -> "threads with all options failed. stderr: " + result.err());
    }

    // ================== most-work options combinations ==================

    @Test
    void mostWorkWithAllOptions() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), "most-work",
            "--top", "2", "--no-native", "--stack-depth", "5", "--intelligent-filter", "1000");
        assertEquals(0, result.exitCode(),
            () -> "most-work with all options failed. stderr: " + result.err());
    }

    // ================== status options combinations ==================

    @Test
    void statusWithAllOptions() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), "status",
            "--top", "1", "--no-native", "--dumps", "2", "--intelligent-filter", "1000");
        assertEquals(0, result.exitCode(),
            () -> "status with all options failed. stderr: " + result.err());
    }

    // ================== no target in replay mode → usage + recorded JVMs ==================

    @Test
    void statusNoTargetInReplayShowsUsageAndRecordedJvms() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), "status");
        // Should show usage (exit 1) and list recorded JVMs
        assertEquals(1, result.exitCode());
        String out = result.out();
        assertTrue(out.contains("Recorded JVMs") || out.contains("Usage") || out.contains("1000"),
            () -> "Should show usage or recorded JVMs. out: " + out);
    }

    @Test
    void threadsNoTargetInReplayShowsUsage() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), "threads");
        assertEquals(1, result.exitCode());
    }

    // ================== implicit status via defaultSubcommand ==================

    @Test
    void implicitStatusWithFilterInReplay() throws Exception {
        Path file = createMinimalRecording(9999, "com.example.MyService");

        // "9999" is not a known subcommand → defaultSubcommand routes to StatusCommand
        RunResult result = Util.run("-f", file.toString(), "9999");
        assertEquals(0, result.exitCode(),
            () -> "Implicit status with PID should work. stderr: " + result.err());
    }

    // ================== recording with custom data type ==================

    @Test
    void recordingWithCustomDataType() throws Exception {
        Path file = Files.createTempFile("custom-data-", ".zip");
        long t = System.currentTimeMillis();

        new RecordingTestBuilder(Main.VERSION)
            .withJvm(7000, "com.example.CustomApp")
                .withThreadDump(busyWorkDumps[0], t)
                .withThreadDump(busyWorkDumps[1], t + 1000)
                .withSystemProperties(SYS_PROPS, t)
                .withCustomData("gc-info", "GC data here", t)
                .build()
            .build(file);
        file.toFile().deleteOnExit();

        // Extra data types shouldn't break normal commands
        RunResult result = Util.run("-f", file.toString(), "threads", "7000");
        assertEquals(0, result.exitCode(),
            () -> "Recording with custom data should not break threads. stderr: " + result.err());
    }

    // ================== recording with many thread dumps ==================

    @Test
    void recordingWithManyDumps() throws Exception {
        Path file = Files.createTempFile("many-dumps-", ".zip");
        long t = System.currentTimeMillis();

        var jvmBuilder = new RecordingTestBuilder(Main.VERSION)
            .withJvm(8000, "com.example.ManyDumpsApp");

        // Add 10 thread dumps
        for (int i = 0; i < 10; i++) {
            jvmBuilder = jvmBuilder.withThreadDump(
                busyWorkDumps[i % busyWorkDumps.length], t + i * 1000);
        }

        jvmBuilder.withSystemProperties(SYS_PROPS, t)
            .build()
            .build(file);
        file.toFile().deleteOnExit();

        // --dumps 3 should limit to 3
        RunResult result = Util.run("-f", file.toString(), "status", "--dumps", "3", "8000");
        assertEquals(0, result.exitCode(),
            () -> "status --dumps 3 on 10-dump recording failed. stderr: " + result.err());
    }

    // ================== recording timestamp ordering ==================

    @Test
    void recordingWithTimestampSetViaCreatedAt() throws Exception {
        Path file = Files.createTempFile("ts-", ".zip");
        long fixedTime = 1700000000000L; // fixed point in time

        new RecordingTestBuilder(Main.VERSION)
            .createdAt(fixedTime)
            .withJvm(1234, "com.example.TsApp")
                .withThreadDump(busyWorkDumps[0], fixedTime)
                .withThreadDump(busyWorkDumps[1], fixedTime + 5000)
                .withSystemProperties(SYS_PROPS, fixedTime)
                .finishedAt(fixedTime + 10000)
                .build()
            .build(file);
        file.toFile().deleteOnExit();

        RunResult result = Util.run("-f", file.toString(), "status", "1234");
        assertEquals(0, result.exitCode(),
            () -> "Recording with explicit timestamps failed. stderr: " + result.err());
    }

    // ================== --file=value form with various commands ==================

    @Test
    void fileEqualsStatusCommand() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("--file=" + file, "status", "1000");
        assertEquals(0, result.exitCode(),
            () -> "--file=value with status failed. stderr: " + result.err());
    }

    @Test
    void fileEqualsDeadlockCommand() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("--file=" + file, "deadlock", "3000");
        // Deadlock detected → exit 2
        assertEquals(2, result.exitCode(),
            () -> "--file=value with deadlock should return exit 2. stderr: " + result.err());
    }

    @Test
    void fileEqualsListCommand() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("--file=" + file, "list");
        assertEquals(0, result.exitCode(),
            () -> "--file=value with list failed. stderr: " + result.err());
    }

    @Test
    void shortFileEqualsForm() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f=" + file, "threads", "1000");
        assertEquals(0, result.exitCode(),
            () -> "-f=value form failed. stderr: " + result.err());
    }

    // ================== subcommand help texts in replay mode ==================

    @ParameterizedTest
    @ValueSource(strings = {"status", "threads", "deadlock", "most-work", "waiting-threads",
        "dependency-graph", "jvm-support"})
    void subcommandHelpInReplayMode(String command) throws Exception {
        Path file = createRichRecording();

        // Run command with --help → always exit 0 with usage text
        RunResult result = Util.run("-f", file.toString(), command, "--help");
        assertEquals(0, result.exitCode(),
            () -> "'" + command + " --help' should exit 0. stderr: " + result.err());
        assertTrue(result.out().contains("Usage") || result.out().contains("usage"),
            () -> "Should show usage text. out: " + result.out());
    }

    // ================== output content validation ==================

    @Test
    void statusOutputContainsAnalyzerSections() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), "status", "1000");
        assertEquals(0, result.exitCode());

        String out = result.out();
        // Status runs multiple analyzers, output has "=== analyzer_name ===" section headers
        assertTrue(out.contains("==="),
            () -> "Status output should contain === section headers. out: " + out);
    }

    @Test
    void deadlockOutputForDeadlockDumpContainsDeadlock() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), "deadlock", "3000");
        assertEquals(2, result.exitCode(),
            () -> "Deadlock detected → exit 2. stderr: " + result.err());
        // Deadlock dump should trigger deadlock detection
        String out = result.out().toLowerCase();
        assertTrue(out.contains("deadlock") || out.contains("lock"),
            () -> "Deadlock output should mention deadlock/lock. out: " + result.out());
    }

    @Test
    void threadsOutputNonEmpty() throws Exception {
        Path file = createRichRecording();

        RunResult result = Util.run("-f", file.toString(), "threads", "1000");
        assertEquals(0, result.exitCode());
        // Should list thread names or states
        assertFalse(result.out().isBlank(),
                "threads output should not be blank");
    }

    // ================== version flag ==================

    @Test
    void versionFlagOutputsVersion() {
        RunResult result = Util.run("-V");
        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains(Main.VERSION),
            () -> "Should output version. out: " + result.out());
    }

    @Test
    void longVersionFlag() {
        RunResult result = Util.run("--version");
        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains(Main.VERSION),
            () -> "Should output version. out: " + result.out());
    }
}