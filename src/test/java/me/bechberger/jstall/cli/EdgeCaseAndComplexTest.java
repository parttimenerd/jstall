package me.bechberger.jstall.cli;

// Use the fluent RunResultAssert returned by RunCommandUtil.run
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
 * - Replay with deadlock / waiting-threads / dependency-tree / jvm-support / most-work
 * - Multi-PID analysis from a single replay file
 * - Filter-based target resolution in replay mode
 * - Recording with system environment data
 * - Recording with failed JVM entry
 * - Recording with zero thread dumps
 * - Analyzer options (--top, --no-native, --stack-depth, --intelligent-filter, --keep, --dump-count)
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

        RunResultAssert result = RunCommandUtil.run("-f", file.toString(), "deadlock", "3000").hasExitCode(2);
        // Deadlock analyzer should detect the deadlock in the dump
        assertTrue(result.get().out().toLowerCase().contains("deadlock") || result.get().out().contains("==="),
            () -> "Should contain deadlock information. out: " + result.get().out());
    }

    @Test
    void deadlockCommandOnNonDeadlockDump() throws Exception {
        Path file = createRichRecording();

        // PID 1000 has busy-work dumps (no deadlock)
        RunCommandUtil.run("-f", file.toString(), "deadlock", "1000").hasNoError();
    }

    // ================== waiting-threads with replay ==================

    @Test
    void waitingThreadsWithReplay() throws Exception {
        Path file = createRichRecording();

        RunCommandUtil.run("-f", file.toString(), "waiting-threads", "1000").hasNoError().output().isNotBlank();
    }

    @Test
    void waitingThreadsWithNoNativeAndStackDepth() throws Exception {
        Path file = createRichRecording();

        RunCommandUtil.run("-f", file.toString(), "waiting-threads",
            "--no-native", "--stack-depth", "5", "1000").hasNoError().output().isNotBlank();
    }

    // ================== dependency-tree with replay ==================

    @Test
    void dependencyGraphWithReplay() throws Exception {
        Path file = createRichRecording();

        RunCommandUtil.run("-f", file.toString(), "dependency-tree", "3000").hasNoError();
    }

    // ================== jvm-support with replay ==================

    @Test
    void jvmSupportWithFutureVersionDate() throws Exception {
        Path file = createRichRecording();

        // java.version.date=2026-01-01 is in the future → JVM is still supported → exit 0, no output
        RunCommandUtil.run("-f", file.toString(), "jvm-support", "1000").hasNoError();
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

        RunCommandUtil.run("-f", file.toString(), "jvm-support", "7777").hasExitCode(10).output().contains("outdated");
    }

    // ================== most-work with options ==================

    @Test
    void mostWorkWithTopOption() throws Exception {
        Path file = createRichRecording();

        RunCommandUtil.run("-f", file.toString(), "most-work", "--top", "5", "1000").hasNoError().output().isNotBlank();
    }

    @Test
    void mostWorkWithStackDepthZero() throws Exception {
        Path file = createRichRecording();

        RunCommandUtil.run("-f", file.toString(), "most-work", "--stack-depth", "0", "1000").hasNoError().output().isNotBlank();
    }

    @Test
    void mostWorkWithNoNative() throws Exception {
        Path file = createRichRecording();

        RunCommandUtil.run("-f", file.toString(), "most-work", "--no-native", "1000").hasNoError().output().isNotBlank();
    }

    // ================== multi-PID analysis in one invocation ==================

    @Test
    void threadsTwoRecordedPids() throws Exception {
        Path file = createRichRecording();

        // Analyze two JVMs in one call
        RunResultAssert result = RunCommandUtil.run("-f", file.toString(), "threads", "1000", "2000").hasNoError();
        // Output should contain headers for both analyses
        assertTrue(result.get().out().contains("1000") || result.get().out().contains("BusyApp"),
            () -> "Output should reference first PID. out: " + result.get().out());
    }

    @Test
    void statusMultiplePids() throws Exception {
        Path file = createRichRecording();

        RunResultAssert result = RunCommandUtil.run("-f", file.toString(), "status", "1000", "2000", "3000");
        // PID 3000 has a deadlock → status uses DeadLockAnalyzer → max exit code = 2
        assertTrue(result.get().exitCode() == 0 || result.get().exitCode() == 2,
            () -> "status for 3 PIDs should succeed or signal deadlock (exit 2). exit: " + result.get().exitCode() + ", stderr: " + result.get().err());
    }

    // ================== filter-based target resolution in replay ==================

    @Test
    void resolveByMainClassFilter() throws Exception {
        Path file = createRichRecording();

        // "Busy" should match "com.example.BusyApp"
        RunCommandUtil.run("-f", file.toString(), "threads", "Busy").hasNoError();
    }

    @Test
    void resolveByMainClassFilterCaseInsensitive() throws Exception {
        Path file = createRichRecording();

        // "normal" (lowercase) should match "com.example.NormalApp"
        RunCommandUtil.run("-f", file.toString(), "threads", "normal").hasNoError();
    }

    @Test
    void filterMatchesMultipleJvms() throws Exception {
        Path file = createRichRecording();

        // "example" matches all 3 JVMs
        RunCommandUtil.run("-f", file.toString(), "threads", "example").hasNoError();
    }

    @Test
    void filterMatchesNothingInReplay() throws Exception {
        Path file = createRichRecording();

        RunCommandUtil.run("-f", file.toString(), "threads", "NonExistentApp").hasError();
    }

    // ================== list command with filter in replay ==================

    @Test
    void listWithFilterInReplay() throws Exception {
        Path file = createRichRecording();

        RunResultAssert result = RunCommandUtil.run("-f", file.toString(), "list", "Busy").hasNoError();
        assertTrue(result.get().out().contains("BusyApp") || result.get().out().contains("1000"),
            () -> "Should list the matching JVM. out: " + result.get().out());
    }

    @Test
    void listWithNoMatchFilterInReplay() throws Exception {
        Path file = createRichRecording();

        RunCommandUtil.run("-f", file.toString(), "list", "ZzzzNotFound").hasExitCode(1).errorOutput().contains("No");
    }

    @Test
    void listAllInReplayShowsAllJvms() throws Exception {
        Path file = createRichRecording();

        RunResultAssert result = RunCommandUtil.run("-f", file.toString(), "list").hasNoError();
        String out = result.get().out();
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
        RunCommandUtil.run("-f", file.toString(), "list").hasNoError();

        // Success JVM should still be analyzable
        RunCommandUtil.run("-f", file.toString(), "status", "5000").hasNoError();
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

        RunCommandUtil.run("-f", file.toString(), "threads", "6000").hasError();
    }

    // ================== empty recording ==================

    @Test
    void emptyRecordingNoJvms() throws Exception {
        Path file = Files.createTempFile("empty-", ".zip");

        new RecordingTestBuilder(Main.VERSION)
            .build(file);
        file.toFile().deleteOnExit();

        RunCommandUtil.run("-f", file.toString(), "list").hasExitCode(1);
    }

    // ================== system environment data in recording ==================

    @Test
    void recordingWithSystemEnvironment() throws Exception {
        Path file = createRichRecording(); // BusyApp has system environment

        // status should still work when system env is present
        RunCommandUtil.run("-f", file.toString(), "status", "1000").hasNoError().output().isNotBlank();
    }

    // ================== --intelligent-filter option ==================

    @Test
    void statusWithIntelligentFilter() throws Exception {
        Path file = createRichRecording();

        RunCommandUtil.run("-f", file.toString(), "status", "--intelligent-filter", "1000").hasNoError();
    }

    @Test
    void threadsWithIntelligentFilter() throws Exception {
        Path file = createRichRecording();

        RunCommandUtil.run("-f", file.toString(), "threads", "--intelligent-filter", "1000").hasNoError();
    }

    // ================== --keep option (in replay, this should be harmless) ==================

    @Test
    void statusWithKeepFlagInReplayMode() throws Exception {
        Path file = createRichRecording();

        // --keep persists dumps to disk; in replay mode, dumps come from ZIP so this is a no-op
        RunCommandUtil.run("-f", file.toString(), "status", "--keep", "1000").hasNoError();
    }

    // ================== --dump-count option to limit dump count ==================

    @Test
    void statusWithDumpsLimiter() throws Exception {
        Path file = createRichRecording(); // BusyApp has 3 dumps

        RunCommandUtil.run("-f", file.toString(), "status", "--dump-count", "2", "1000").hasNoError();
    }

    @Test
    void threadsWithDumpsLimiter() throws Exception {
        Path file = createRichRecording();

        RunCommandUtil.run("-f", file.toString(), "threads", "--dump-count", "1", "1000")
            .hasError()
            .errorOutput().contains("analyzer 'threads' requires at least 2 dumps");
    }

    // ================== cross-analyzer verification: same recording, many commands ==================

    @ParameterizedTest
    @ValueSource(strings = {"status", "threads", "deadlock", "most-work", "waiting-threads",
        "dependency-tree", "jvm-support"})
    void allAnalyzerCommandsOnBusyAppRecording(String command) throws Exception {
        Path file = createRichRecording();

        RunResultAssert result = RunCommandUtil.run("-f", file.toString(), command, "1000").hasNoError();
        // Commands like deadlock, jvm-support, dependency-tree may produce no output if nothing found; other commands should produce output
        if (!command.equals("deadlock") && !command.equals("jvm-support") && !command.equals("dependency-tree")) {
            result.output().isNotBlank();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "threads", "most-work", "waiting-threads",
        "dependency-tree", "jvm-support"})
    void allNonDeadlockAnalyzerCommandsOnDeadlockAppRecording(String command) throws Exception {
        Path file = createRichRecording();

        RunResultAssert result = RunCommandUtil.run("-f", file.toString(), command, "3000");
        // status may return 2 because it includes DeadLockAnalyzer
        assertTrue(result.get().exitCode() == 0 || result.get().exitCode() == 2,
            () -> "'" + command + "' on deadlock dump returned unexpected exit code " + result.get().exitCode() + ". stderr: " + result.get().err());
    }

    @Test
    void deadlockCommandOnDeadlockAppRecordingReturnsTwo() throws Exception {
        Path file = createRichRecording();

        RunCommandUtil.run("-f", file.toString(), "deadlock", "3000").hasExitCode(2);
    }

    // ================== threads options combinations ==================

    @Test
    void threadsWithAllOptions() throws Exception {
        Path file = createRichRecording();

        RunCommandUtil.run("-f", file.toString(), "threads",
            "--no-native", "--dump-count", "2", "--intelligent-filter", "1000").hasNoError();
    }

    // ================== most-work options combinations ==================

    @Test
    void mostWorkWithAllOptions() throws Exception {
        Path file = createRichRecording();

        RunCommandUtil.run("-f", file.toString(), "most-work",
            "--top", "2", "--no-native", "--stack-depth", "5", "--intelligent-filter", "1000").hasNoError();
    }

    // ================== status options combinations ==================

    @Test
    void statusWithAllOptions() throws Exception {
        Path file = createRichRecording();

        RunCommandUtil.run("-f", file.toString(), "status",
            "--top", "1", "--no-native", "--dump-count", "2", "--intelligent-filter", "1000").hasNoError();
    }

    // ================== no target in replay mode → usage + recorded JVMs ==================

    @Test
    void statusNoTargetInReplayShowsUsageAndRecordedJvms() throws Exception {
        Path file = createRichRecording();

        RunResultAssert result = RunCommandUtil.run("-f", file.toString(), "status").hasExitCode(1);
        String out = result.get().out();
        assertTrue(out.contains("Recorded JVMs") || out.contains("Usage") || out.contains("1000"),
            () -> "Should show usage or recorded JVMs. out: " + out);
    }

    @Test
    void threadsNoTargetInReplayShowsUsage() throws Exception {
        Path file = createRichRecording();

        RunCommandUtil.run("-f", file.toString(), "threads").hasExitCode(1);
    }

    // ================== implicit status via defaultSubcommand ==================

    @Test
    void implicitStatusWithFilterInReplay() throws Exception {
        Path file = createMinimalRecording(9999, "com.example.MyService");

        // "9999" is not a known subcommand → defaultSubcommand routes to StatusCommand
        RunCommandUtil.run("-f", file.toString(), "9999").hasNoError().output().isNotBlank();
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
        RunCommandUtil.run("-f", file.toString(), "threads", "7000").hasNoError().output().isNotBlank();
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

        // --dump-count 3 should limit to 3
        RunCommandUtil.run("-f", file.toString(), "status", "--dump-count", "3", "8000").hasNoError();
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

        RunCommandUtil.run("-f", file.toString(), "status", "1234").hasNoError();
    }

    // ================== --file=value form with various commands ==================

    @Test
    void fileEqualsStatusCommand() throws Exception {
        Path file = createRichRecording();

        RunCommandUtil.run("--file=" + file, "status", "1000").hasNoError();
    }

    @Test
    void fileEqualsDeadlockCommand() throws Exception {
        Path file = createRichRecording();

        RunCommandUtil.run("--file=" + file, "deadlock", "3000").hasExitCode(2);
    }

    @Test
    void fileEqualsListCommand() throws Exception {
        Path file = createRichRecording();

        RunCommandUtil.run("--file=" + file, "list").hasNoError();
    }

    @Test
    void shortFileEqualsForm() throws Exception {
        Path file = createRichRecording();

        RunCommandUtil.run("-f=" + file, "threads", "1000").hasNoError();
    }

    // ================== subcommand help texts in replay mode ==================

    @ParameterizedTest
    @ValueSource(strings = {"status", "threads", "deadlock", "most-work", "waiting-threads",
        "dependency-tree", "jvm-support"})
    void subcommandHelpInReplayMode(String command) throws Exception {
        Path file = createRichRecording();

        // Run command with --help → always exit 0 with usage text
        RunCommandUtil.run("-f", file.toString(), command, "--help").hasNoError().output().contains("Usage");
    }

    // ================== output content validation ==================

    @Test
    void statusOutputContainsAnalyzerSections() throws Exception {
        Path file = createRichRecording();

        RunResultAssert result = RunCommandUtil.run("-f", file.toString(), "status", "1000").hasNoError();

        String out = result.get().out();
        // Status runs multiple analyzers, output has "=== analyzer_name ===" section headers
        assertTrue(out.contains("==="),
            () -> "Status output should contain === section headers. out: " + out);
    }

    @Test
    void deadlockOutputForDeadlockDumpContainsDeadlock() throws Exception {
        Path file = createRichRecording();

        RunResultAssert result = RunCommandUtil.run("-f", file.toString(), "deadlock", "3000").hasExitCode(2);
        // Deadlock dump should trigger deadlock detection
        String out = result.get().out().toLowerCase();
        assertTrue(out.contains("deadlock") || out.contains("lock"),
            () -> "Deadlock output should mention deadlock/lock. out: " + result.get().out());
    }

    @Test
    void threadsOutputNonEmpty() throws Exception {
        Path file = createRichRecording();

        RunCommandUtil.run("-f", file.toString(), "threads", "1000").hasNoError().output().isNotBlank();
    }

}