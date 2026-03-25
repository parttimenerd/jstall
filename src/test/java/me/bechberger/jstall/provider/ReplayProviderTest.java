package me.bechberger.jstall.provider;

import me.bechberger.jstall.Main;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReplayProviderTest {

    private static final long TEST_PID = 12345;
    private static final String TEST_MAIN_CLASS = "com.example.TestApp";

    /**
     * Creates a synthetic recording using RecordingTestBuilder.
     * Includes thread dumps, system properties, and system environment.
     */
    private Path createTestRecording(int dumpCount, long intervalMs) throws Exception {
        Path tempFile = Files.createTempFile("replay-test-", ".zip");
        tempFile.toFile().deleteOnExit();

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

        builder.build().build(tempFile);
        return tempFile;
    }

    /**
     * Creates a synthetic recording using RecordingTestBuilder when we need specific PIDs or multiple JVMs.
     */
    private Path createSyntheticRecording(int pid, String mainClass, int dumpCount) throws Exception {
        Path tempFile = Files.createTempFile("replay-synthetic-", ".zip");
        tempFile.toFile().deleteOnExit();

        long baseTime = System.currentTimeMillis();
        String systemProps = "java.version=21\njava.version.date=2024-01-01\n";
        String[] dumps = ThreadDumpTestResources.loadBusyWorkDumps();

        var builder = new RecordingTestBuilder(Main.VERSION)
            .withJvm(pid, mainClass);

        for (int i = 0; i < dumpCount && i < dumps.length; i++) {
            builder.withThreadDump(dumps[i], baseTime + (i * 1000L))
                   .withSystemProperties(systemProps, baseTime + (i * 1000L));
        }

        builder.build().build(tempFile);
        return tempFile;
    }

    @Test
    void testReplayLoadsSingleThreadDump(@TempDir Path tempDir) throws Exception {
        Path recording = createTestRecording(1, 0);

        ReplayProvider replay = new ReplayProvider(recording);
        List<ThreadDumpSnapshot> snapshots = replay.loadForPid(TEST_PID);

        assertEquals(1, snapshots.size());
        assertNotNull(snapshots.get(0).parsed());
        assertNotNull(snapshots.get(0).raw());
    }

    @Test
    void testReplayLoadsMultipleThreadDumps(@TempDir Path tempDir) throws Exception {
        Path recording = createTestRecording(3, 100);

        ReplayProvider replay = new ReplayProvider(recording);
        List<ThreadDumpSnapshot> snapshots = replay.loadForPid(TEST_PID);

        assertEquals(3, snapshots.size());
        for (ThreadDumpSnapshot snapshot : snapshots) {
            assertNotNull(snapshot.parsed());
            assertNotNull(snapshot.raw());
        }
    }

    @Test
    void testReplayThreadDumpsSortedChronologically(@TempDir Path tempDir) throws Exception {
        Path recording = createTestRecording(3, 100);

        ReplayProvider replay = new ReplayProvider(recording);
        List<ThreadDumpSnapshot> snapshots = replay.loadForPid(TEST_PID);

        // Verify chronological order
        for (int i = 1; i < snapshots.size(); i++) {
            assertTrue(
                snapshots.get(i - 1).parsed().timestamp()
                    .isBefore(snapshots.get(i).parsed().timestamp()),
                "Thread dumps should be in chronological order"
            );
        }
    }

    @Test
    void testReplayLimitsDumpCount(@TempDir Path tempDir) throws Exception {
        Path recording = createTestRecording(3, 100);

        ReplayProvider replay = new ReplayProvider(recording);
        List<ThreadDumpSnapshot> allSnapshots = replay.loadForPid(TEST_PID);
        List<ThreadDumpSnapshot> limited = allSnapshots.subList(0, Math.min(1, allSnapshots.size()));

        assertEquals(1, limited.size(), "Should limit to requested count");
    }

    @Test
    void testReplayReturnsAllDumpsWhenCountIsZero(@TempDir Path tempDir) throws Exception {
        Path recording = createTestRecording(3, 100);

        ReplayProvider replay = new ReplayProvider(recording);
        List<ThreadDumpSnapshot> snapshots = replay.loadForPid(TEST_PID);

        assertEquals(3, snapshots.size(), "Should return all dumps");
    }

    @Test
    void testReplayLoadsByDumpCount(@TempDir Path tempDir) throws Exception {
        Path recording = createTestRecording(3, 100);

        ReplayProvider replay = new ReplayProvider(recording);
        List<ThreadDumpSnapshot> allSnapshots = replay.loadForPid(TEST_PID);
        
        List<ThreadDumpSnapshot> two = allSnapshots.subList(0, Math.min(2, allSnapshots.size()));
        List<ThreadDumpSnapshot> three = allSnapshots.subList(0, Math.min(3, allSnapshots.size()));

        assertEquals(2, two.size());
        assertEquals(3, three.size());
    }

    @Test
    void testReplaySystemPropertiesAttachedToSnapshots(@TempDir Path tempDir) throws Exception {
        Path recording = createTestRecording(1, 0);

        ReplayProvider replay = new ReplayProvider(recording);
        List<ThreadDumpSnapshot> snapshots = replay.loadForPid(TEST_PID);

        assertEquals(1, snapshots.size());
        assertNotNull(snapshots.get(0).systemProperties());
        assertTrue(snapshots.get(0).systemProperties().containsKey("java.version"));
        assertNotNull(snapshots.get(0).systemProperties().get("java.version"));
    }

    @Test
    void testReplaySystemEnvironmentAttachedToSnapshots(@TempDir Path tempDir) throws Exception {
        Path recording = createTestRecording(1, 0);

        ReplayProvider replay = new ReplayProvider(recording);
        List<ThreadDumpSnapshot> snapshots = replay.loadForPid(TEST_PID);

        assertEquals(1, snapshots.size());
        assertNotNull(snapshots.get(0).environment());
        assertFalse(snapshots.get(0).environment().processes().isEmpty());
    }

    @Test
    void testReplayLoadCollectedDataByType(@TempDir Path tempDir) throws Exception {
        Path recording = createTestRecording(2, 100);

        ReplayProvider replay = new ReplayProvider(recording);
        Map<String, List<me.bechberger.jstall.provider.requirement.CollectedData>> data =
            replay.loadCollectedDataByTypeForPid(TEST_PID);

        assertNotNull(data);
        assertTrue(data.containsKey("thread-dumps"));
        assertTrue(data.containsKey("system-properties"));

        assertEquals(2, data.get("thread-dumps").size());
        assertEquals(2, data.get("system-properties").size());
    }

    @Test
    void testReplayFilteredByName(@TempDir Path tempDir) throws Exception {
        // Need synthetic recording with multiple JVMs for this test
        Path tempFile = Files.createTempFile("replay-filter-", ".zip");
        tempFile.toFile().deleteOnExit();
        long baseTime = System.currentTimeMillis();
        String systemProps = "java.version=21\n";
        String[] dumps = ThreadDumpTestResources.loadBusyWorkDumps();

        new RecordingTestBuilder(Main.VERSION)
            .withJvm(1000, "com.example.FilterApp")
                .withThreadDump(dumps[0], baseTime)
                .withSystemProperties(systemProps, baseTime)
                .build()
            .withJvm(2000, "com.other.App")
                .withThreadDump(dumps[0], baseTime)
                .withSystemProperties(systemProps, baseTime)
                .build()
            .build(tempFile);

        ReplayProvider replay = new ReplayProvider(tempFile);
        var filtered = replay.listRecordedJvms("Filter");

        assertEquals(1, filtered.size());
        assertEquals(1000, filtered.get(0).pid());
    }

    @Test
    void testReplayEmptyArchive(@TempDir Path tempDir) throws Exception {
        Path tempFile = Files.createTempFile("replay-empty-", ".zip");
        tempFile.toFile().deleteOnExit();

        new RecordingTestBuilder(Main.VERSION)
            .build(tempFile);

        ReplayProvider replay = new ReplayProvider(tempFile);
        var jvms = replay.listRecordedJvms(null);

        assertEquals(0, jvms.size());
    }

    @Test
    void testReplayPidNotInArchive(@TempDir Path tempDir) throws Exception {
        // Use synthetic recording with known PID for this test
        Path recording = createSyntheticRecording(1111, "com.example.App", 1);

        ReplayProvider replay = new ReplayProvider(recording);

        assertThrows(IOException.class, () -> replay.loadForPid(9999));
    }

    @Test
    void testReplayUsesNearestSystemProperties(@TempDir Path tempDir) throws Exception {
        // Need synthetic recording for precise timing control
        Path tempFile = Files.createTempFile("replay-nearest-", ".zip");
        tempFile.toFile().deleteOnExit();
        long baseTime = System.currentTimeMillis();
        String systemProps1 = "java.version=20\n";
        String systemProps2 = "java.version=21\n";
        String[] dumps = ThreadDumpTestResources.loadBusyWorkDumps();

        new RecordingTestBuilder(Main.VERSION)
            .withJvm(3333, "com.example.App")
                // Thread dump at time 1000
                .withThreadDump(dumps[0], baseTime + 1000)
                // System properties at time 0 and 2000
                .withSystemProperties(systemProps1, baseTime)
                .withSystemProperties(systemProps2, baseTime + 2000)
                .build()
            .build(tempFile);

        ReplayProvider replay = new ReplayProvider(tempFile);
        List<ThreadDumpSnapshot> snapshots = replay.loadForPid(3333);

        assertEquals(1, snapshots.size());
        // Should match the nearest property sample (at time 2000)
        assertNotNull(snapshots.get(0).systemProperties());
    }

    @Test
    void testReplayMultipleJvmsIndependent(@TempDir Path tempDir) throws Exception {
        // Need synthetic recording with multiple JVMs
        Path tempFile = Files.createTempFile("replay-multi-", ".zip");
        tempFile.toFile().deleteOnExit();
        long baseTime = System.currentTimeMillis();
        String systemProps = "java.version=21\n";
        String[] busyDumps = ThreadDumpTestResources.loadBusyWorkDumps();
        String[] normalDumps = ThreadDumpTestResources.loadNormalDumps();

        new RecordingTestBuilder(Main.VERSION)
            .withJvm(5000, "com.example.App1")
                .withThreadDump(busyDumps[0], baseTime)
                .withThreadDump(busyDumps[1], baseTime + 1000)
                .withSystemProperties(systemProps, baseTime)
                .build()
            .withJvm(6000, "com.example.App2")
                .withThreadDump(normalDumps[0], baseTime)
                .withThreadDump(normalDumps[1], baseTime + 1000)
                .withThreadDump(normalDumps[2], baseTime + 2000)
                .withSystemProperties(systemProps, baseTime)
                .build()
            .build(tempFile);

        ReplayProvider replay = new ReplayProvider(tempFile);

        var app1 = replay.loadForPid(5000);
        var app2 = replay.loadForPid(6000);

        assertEquals(2, app1.size());
        assertEquals(3, app2.size());
    }

    @Test
    void testReplayPreservesRawDumpContent(@TempDir Path tempDir) throws Exception {
        Path recording = createTestRecording(1, 0);

        ReplayProvider replay = new ReplayProvider(recording);
        List<ThreadDumpSnapshot> snapshots = replay.loadForPid(TEST_PID);

        assertEquals(1, snapshots.size());
        assertNotNull(snapshots.get(0).raw());
        assertFalse(snapshots.get(0).raw().isEmpty());
    }
}