package me.bechberger.jstall.provider;

import me.bechberger.jstall.Main;
import me.bechberger.jstall.util.JVMDiscovery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class RecordingProviderTest {

    private RecordingProvider provider;
    private String[] busyWorkDumps;

    @BeforeEach
    void setUp() throws Exception {
        provider = new RecordingProvider(Main.VERSION);
        busyWorkDumps = ThreadDumpTestResources.loadBusyWorkDumps();
    }

    @Test
    void testCreateSimpleRecording(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("recording.zip");
        long timestamp = System.currentTimeMillis();

        String systemProps = "java.version=21\njava.version.date=2024-01-01\n";
        String systemEnv = "{\"processes\": []}";

        new RecordingTestBuilder(Main.VERSION)
            .createdAt(timestamp)
            .withJvm(12345, "com.example.App")
                .withThreadDump(busyWorkDumps[0], timestamp)
                .withThreadDump(busyWorkDumps[1], timestamp + 1000)
                .withSystemProperties(systemProps, timestamp)
                .build()
            .build(outputFile);

        assertTrue(Files.exists(outputFile));
        assertTrue(Files.size(outputFile) > 0);

        // Verify metadata
        ReplayProvider replay = new ReplayProvider(outputFile);
        List<JVMDiscovery.JVMProcess> recorded = replay.listRecordedJvms(null);
        assertEquals(1, recorded.size());
        assertEquals(12345, recorded.get(0).pid());
        assertEquals("com.example.App", recorded.get(0).mainClass());
    }

    @Test
    void testRecordingMultipleJvms(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("multi-jvm-recording.zip");
        long timestamp = System.currentTimeMillis();
        String[] normalDumps = ThreadDumpTestResources.loadNormalDumps();
        String systemProps = "java.version=21\njava.version.date=2024-01-01\n";

        new RecordingTestBuilder(Main.VERSION)
            .withJvm(111, "com.example.App1")
                .withThreadDump(busyWorkDumps[0], timestamp)
                .withSystemProperties(systemProps, timestamp)
                .build()
            .withJvm(222, "com.example.App2")
                .withThreadDump(normalDumps[0], timestamp)
                .withSystemProperties(systemProps, timestamp)
                .build()
            .withJvm(333, "com.example.App3")
                .withThreadDump(normalDumps[1], timestamp)
                .withSystemProperties(systemProps, timestamp)
                .build()
            .build(outputFile);

        ReplayProvider replay = new ReplayProvider(outputFile);
        List<JVMDiscovery.JVMProcess> recorded = replay.listRecordedJvms(null);
        assertEquals(3, recorded.size());

        // Verify PIDs are sorted
        assertEquals(111, recorded.get(0).pid());
        assertEquals(222, recorded.get(1).pid());
        assertEquals(333, recorded.get(2).pid());

        // Verify all can be filtered
        List<JVMDiscovery.JVMProcess> filtered = replay.listRecordedJvms("App2");
        assertEquals(1, filtered.size());
        assertEquals(222, filtered.get(0).pid());
    }

    @Test
    void testRecordingWithMultipleDumpsPerJvm(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("multi-dump-recording.zip");
        long baseTime = System.currentTimeMillis();
        String systemProps = "java.version=21\njava.version.date=2024-01-01\n";

        new RecordingTestBuilder(Main.VERSION)
            .withJvm(12345, "com.example.App")
                .withThreadDump(busyWorkDumps[0], baseTime)
                .withThreadDump(busyWorkDumps[1], baseTime + 1000)
                .withThreadDump(busyWorkDumps[2], baseTime + 2000)
                .withSystemProperties(systemProps, baseTime)
                .withSystemProperties(systemProps, baseTime + 1000)
                .withSystemProperties(systemProps, baseTime + 2000)
                .build()
            .build(outputFile);

        ReplayProvider replay = new ReplayProvider(outputFile);
        List<JVMDiscovery.JVMProcess> recorded = replay.listRecordedJvms(null);
        assertEquals(1, recorded.size());

        // Load thread dumps
        var snapshots = replay.loadForPid(12345);
        assertEquals(3, snapshots.size());

        // Verify chronological order
        assertTrue(
            snapshots.get(0).parsed().timestamp()
                .isBefore(snapshots.get(1).parsed().timestamp()),
            "Thread dumps should be sorted chronologically"
        );
    }

    @Test
    void testRecordingMetadataPresent(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("recording-with-metadata.zip");
        long timestamp = System.currentTimeMillis();
        String systemProps = "java.version=21\njava.version.date=2024-01-01\n";

        new RecordingTestBuilder(Main.VERSION)
            .createdAt(timestamp)
            .withJvm(999, "com.test.TestApp")
                .withThreadDump(busyWorkDumps[0], timestamp)
                .withSystemProperties(systemProps, timestamp)
                .finishedAt(timestamp + 500)
                .build()
            .build(outputFile);

        ReplayProvider replay = new ReplayProvider(outputFile);
        var metadata = replay.metadata();

        assertNotNull(metadata);
        assertEquals(RecordingProvider.FORMAT_VERSION, 
            (long)(double)metadata.get("format_version"));
        assertEquals(Main.VERSION, metadata.get("version"));
    }

    @Test
    void testRecordingWithCollectedData(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("recording-with-data.zip");
        long timestamp = System.currentTimeMillis();
        String systemProps = "java.version=21\njava.version.date=2024-01-01\njava.vm.name=HotSpot\n";

        new RecordingTestBuilder(Main.VERSION)
            .withJvm(777, "com.example.DataApp")
                .withThreadDump(busyWorkDumps[0], timestamp)
                .withThreadDump(busyWorkDumps[1], timestamp + 1000)
                .withSystemProperties(systemProps, timestamp)
                .withSystemEnvironment(
                    "{\"processes\": [{\"pid\": 777, \"command\": \"java -cp ...\"}]}",
                    timestamp)
                .build()
            .build(outputFile);

        ReplayProvider replay = new ReplayProvider(outputFile);
        Map<String, java.util.List<me.bechberger.jstall.provider.requirement.CollectedData>> data =
            replay.loadCollectedDataByTypeForPid(777);

        assertNotNull(data);
        assertTrue(data.containsKey("thread-dumps"));
        assertTrue(data.containsKey("system-properties"));
        assertTrue(data.containsKey("system-environment"));

        assertEquals(2, data.get("thread-dumps").size());
        assertEquals(1, data.get("system-properties").size());
        assertEquals(1, data.get("system-environment").size());
    }

    @Test
    void testEmptyRecording(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("empty-recording.zip");

        new RecordingTestBuilder(Main.VERSION)
            .build(outputFile);

        assertTrue(Files.exists(outputFile));
        ReplayProvider replay = new ReplayProvider(outputFile);
        List<JVMDiscovery.JVMProcess> recorded = replay.listRecordedJvms(null);
        assertEquals(0, recorded.size());
    }

    @Test
    void testRecordingHasPidCheck(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("hasPid-recording.zip");
        long timestamp = System.currentTimeMillis();
        String systemProps = "java.version=21\n";

        new RecordingTestBuilder(Main.VERSION)
            .withJvm(5000, "com.example.App")
                .withThreadDump(busyWorkDumps[0], timestamp)
                .withSystemProperties(systemProps, timestamp)
                .build()
            .build(outputFile);

        ReplayProvider replay = new ReplayProvider(outputFile);

        assertTrue(replay.hasPid(5000));
        assertFalse(replay.hasPid(9999));
    }

    @Test
    void testRecordingPidNotFound(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("notfound-recording.zip");
        long timestamp = System.currentTimeMillis();
        String systemProps = "java.version=21\n";

        new RecordingTestBuilder(Main.VERSION)
            .withJvm(5000, "com.example.App")
                .withThreadDump(busyWorkDumps[0], timestamp)
                .withSystemProperties(systemProps, timestamp)
                .build()
            .build(outputFile);

        ReplayProvider replay = new ReplayProvider(outputFile);

        assertThrows(java.io.IOException.class, () -> replay.loadForPid(9999));
    }

    @Test
    void testRecordingZipStructure(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("structure-recording.zip");
        long timestamp = System.currentTimeMillis();
        String systemProps = "java.version=21\n";

        new RecordingTestBuilder(Main.VERSION)
            .withJvm(6000, "com.example.App")
                .withThreadDump(busyWorkDumps[0], timestamp)
                .withSystemProperties(systemProps, timestamp)
                .build()
            .build(outputFile);

        // Verify ZIP structure
        try (ZipFile zipFile = new ZipFile(outputFile.toFile())) {
            assertNotNull(zipFile.getEntry("structure-recording/metadata.json"));
            assertNotNull(zipFile.getEntry("structure-recording/README.md"));
            assertNotNull(zipFile.getEntry("structure-recording/6000/thread-dumps/000-" + timestamp + ".txt"));
        }
    }

    @Test
    void testRecordingVersionInformation(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("version-recording.zip");

        new RecordingTestBuilder(Main.VERSION)
            .withJvm(7000, "com.example.VersionApp")
                .withThreadDump(busyWorkDumps[0], System.currentTimeMillis())
                .build()
            .build(outputFile);

        ReplayProvider replay = new ReplayProvider(outputFile);
        assertEquals(Main.VERSION, replay.metadata().get("version"));
    }
}