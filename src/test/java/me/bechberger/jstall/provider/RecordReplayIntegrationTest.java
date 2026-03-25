package me.bechberger.jstall.provider;

import me.bechberger.jstall.Main;
import me.bechberger.jstall.analyzer.*;
import me.bechberger.jstall.analyzer.impl.StatusAnalyzer;
import me.bechberger.jstall.analyzer.impl.ThreadsAnalyzer;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the full record-replay workflow.
 * Tests that data recorded in a ZIP file can be correctly loaded and analyzed.
 */
class RecordReplayIntegrationTest {

    private static final long TEST_PID = 50000;
    private static final String TEST_MAIN_CLASS = "com.example.IntegrationTestApp";

    /**
     * Creates a synthetic recording using RecordingTestBuilder.
     */
    private Path createRecording(Path outputFile, int dumpCount, long intervalMs) throws Exception {
        long baseTime = System.currentTimeMillis();
        String systemProps = "java.version=21\njava.version.date=2024-01-01\n";
        String systemEnv = "{\"processes\": [{\"pid\": " + TEST_PID + ", \"command\": \"java test\", \"cpuTimeNanos\": 1000000}]}";
        String[] dumps = ThreadDumpTestResources.loadBusyWorkDumps();

        var builder = new RecordingTestBuilder(Main.VERSION)
            .withJvm(TEST_PID, TEST_MAIN_CLASS);

        long effectiveInterval = intervalMs > 0 ? intervalMs : 1000;
        for (int i = 0; i < dumpCount && i < dumps.length; i++) {
            builder.withThreadDump(dumps[i], baseTime + (i * effectiveInterval))
                   .withSystemProperties(systemProps, baseTime + (i * effectiveInterval));
        }
        builder.withSystemEnvironment(systemEnv, baseTime);

        builder.build().build(outputFile);
        return outputFile;
    }

    @Test
    void testRecordThenReplayAndAnalyze(@TempDir Path tempDir) throws Exception {
        Path recordingFile = tempDir.resolve("test-recording.zip");

        createRecording(recordingFile, 2, 100);

        assertTrue(Files.exists(recordingFile));

        // Replay and load
        ReplayProvider replay = new ReplayProvider(recordingFile);
        List<ThreadDumpSnapshot> snapshots = replay.loadForPid(TEST_PID);
        assertEquals(2, snapshots.size());

        // Create resolved data and analyze
        ResolvedData data = ResolvedData.fromDumps(snapshots);
        Map<String, Object> options = Map.of("dumps", 2);

        // Test with different analyzers
        Analyzer statusAnalyzer = new StatusAnalyzer();
        AnalyzerResult status = statusAnalyzer.analyze(data, options);
        assertNotNull(status);

        Analyzer threadsAnalyzer = new ThreadsAnalyzer();
        AnalyzerResult threads = threadsAnalyzer.analyze(data, options);
        assertNotNull(threads);
    }

    @Test
    void testRecordMultipleJvmsThenReplayEach(@TempDir Path tempDir) throws Exception {
        Path recordingFile = tempDir.resolve("multi-jvm-recording.zip");
        long baseTime = System.currentTimeMillis();
        String systemProps = "java.version=21\njava.version.date=2024-01-01\n";
        String[] busyDumps = ThreadDumpTestResources.loadBusyWorkDumps();
        String[] normalDumps = ThreadDumpTestResources.loadNormalDumps();

        // Record multiple JVMs (need synthetic for multiple JVMs)
        new RecordingTestBuilder(Main.VERSION)
            .withJvm(20001, "com.example.App1")
                .withThreadDump(busyDumps[0], baseTime)
                .withThreadDump(busyDumps[1], baseTime + 1000)
                .withSystemProperties(systemProps, baseTime)
                .build()
            .withJvm(20002, "com.example.App2")
                .withThreadDump(normalDumps[0], baseTime)
                .withThreadDump(normalDumps[1], baseTime + 1000)
                .withSystemProperties(systemProps, baseTime)
                .build()
            .build(recordingFile);

        // Replay each JVM independently
        ReplayProvider replay = new ReplayProvider(recordingFile);

        var app1Snapshots = replay.loadForPid(20001);
        assertEquals(2, app1Snapshots.size());
        ResolvedData app1Data = ResolvedData.fromDumps(app1Snapshots);
        assertNotNull(app1Data);

        var app2Snapshots = replay.loadForPid(20002);
        assertEquals(2, app2Snapshots.size());
        ResolvedData app2Data = ResolvedData.fromDumps(app2Snapshots);
        assertNotNull(app2Data);
    }

    @Test
    void testReplayWithCollectedDataPreserved(@TempDir Path tempDir) throws Exception {
        Path recordingFile = tempDir.resolve("collected-data-recording.zip");

        createRecording(recordingFile, 3, 100);

        // Replay and verify all data is present
        ReplayProvider replay = new ReplayProvider(recordingFile);

        var snapshots = replay.loadForPid(TEST_PID);
        assertEquals(3, snapshots.size());

        // All snapshots should have system properties and environment
        for (var snapshot : snapshots) {
            assertNotNull(snapshot.systemProperties(), "Should have system properties");
            assertTrue(snapshot.systemProperties().containsKey("java.version"));
            assertNotNull(snapshot.environment(), "Should have system environment");
        }

        // Verify collected data can be loaded separately
        var collectedData = replay.loadCollectedDataByTypeForPid(TEST_PID);
        assertEquals(3, collectedData.get("thread-dumps").size());
        assertEquals(3, collectedData.get("system-properties").size());
        assertTrue(collectedData.containsKey("system-environment"));
    }

    @Test
    void testReplayWithResolvedDataConstruction(@TempDir Path tempDir) throws Exception {
        Path recordingFile = tempDir.resolve("resolved-data-recording.zip");

        createRecording(recordingFile, 2, 100);

        // Replay and construct resolved data
        ReplayProvider replay = new ReplayProvider(recordingFile);
        List<ThreadDumpSnapshot> snapshots = replay.loadForPid(TEST_PID);

        // Get collected data for the PID
        var collectedDataByType = replay.loadCollectedDataByTypeForPid(TEST_PID);

        // Construct resolved data with collected data
        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(snapshots, collectedDataByType);

        assertNotNull(data);
        assertEquals(2, data.dumps().size());
    }

    @Test
    void testReplayConsistencyAcrossMultipleCalls(@TempDir Path tempDir) throws Exception {
        Path recordingFile = tempDir.resolve("consistency-recording.zip");

        createRecording(recordingFile, 2, 100);

        ReplayProvider replay = new ReplayProvider(recordingFile);

        // Load multiple times
        List<ThreadDumpSnapshot> first = replay.loadForPid(TEST_PID);
        List<ThreadDumpSnapshot> second = replay.loadForPid(TEST_PID);
        List<ThreadDumpSnapshot> third = replay.loadForPid(TEST_PID);

        // All should be equal
        assertEquals(first.size(), second.size());
        assertEquals(second.size(), third.size());

        // Content should match
        for (int i = 0; i < first.size(); i++) {
            assertEquals(
                first.get(i).raw(),
                second.get(i).raw(),
                "Raw dumps should be consistent"
            );
        }
    }

    @Test
    void testRecordReplayWorkflowWithDumpCount(@TempDir Path tempDir) throws Exception {
        Path recordingFile = tempDir.resolve("dump-count-recording.zip");

        createRecording(recordingFile, 3, 100);

        ReplayProvider replay = new ReplayProvider(recordingFile);

        // Load all dumps and limit as needed
        List<ThreadDumpSnapshot> allDumps = replay.loadForPid(TEST_PID);
        List<ThreadDumpSnapshot> one = allDumps.subList(0, Math.min(1, allDumps.size()));
        List<ThreadDumpSnapshot> two = allDumps.subList(0, Math.min(2, allDumps.size()));

        assertEquals(1, one.size());
        assertEquals(2, two.size());
        assertEquals(3, allDumps.size());
    }

    @Test
    void testReplayProviderStateIndependence(@TempDir Path tempDir) throws Exception {
        Path recording1 = tempDir.resolve("recording1.zip");
        Path recording2 = tempDir.resolve("recording2.zip");
        long baseTime = System.currentTimeMillis();
        String systemProps = "java.version=21\njava.version.date=2024-01-01\n";
        String[] busyDumps = ThreadDumpTestResources.loadBusyWorkDumps();
        String[] normalDumps = ThreadDumpTestResources.loadNormalDumps();

        // Create two separate recordings (need synthetic for different PIDs)
        new RecordingTestBuilder(Main.VERSION)
            .withJvm(70001, "com.example.App1")
                .withThreadDump(busyDumps[0], baseTime)
                .withSystemProperties(systemProps, baseTime)
                .build()
            .build(recording1);

        new RecordingTestBuilder(Main.VERSION)
            .withJvm(70002, "com.example.App2")
                .withThreadDump(normalDumps[0], baseTime)
                .withSystemProperties(systemProps, baseTime)
                .build()
            .build(recording2);

        // Create separate replay providers
        ReplayProvider replay1 = new ReplayProvider(recording1);
        ReplayProvider replay2 = new ReplayProvider(recording2);

        // Each should only see their own data
        var list1 = replay1.listRecordedJvms(null);
        var list2 = replay2.listRecordedJvms(null);

        assertEquals(1, list1.size());
        assertEquals(1, list2.size());
        assertEquals(70001, list1.get(0).pid());
        assertEquals(70002, list2.get(0).pid());

        // Cross-access should fail appropriately
        assertThrows(Exception.class, () -> replay1.loadForPid(70002));
        assertThrows(Exception.class, () -> replay2.loadForPid(70001));
    }
}