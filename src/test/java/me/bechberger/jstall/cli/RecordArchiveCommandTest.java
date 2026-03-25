package me.bechberger.jstall.cli;

import me.bechberger.femtocli.RunResult;
import me.bechberger.jstall.Main;
import me.bechberger.jstall.provider.RecordingTestBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RecordArchiveCommandTest {

    @Test
    void testRecordSummaryPrintsReadme(@TempDir Path tempDir) throws Exception {
        Path recordingFile = tempDir.resolve("summary-recording.zip");
        createRecording(recordingFile);

        RunCommandUtil.run("record", "summary", recordingFile.toString()).hasNoError().hasOutputContaining("JStall Recording Archive").hasOutputContaining("Recorded JVMs:").hasOutputContaining("12345");
    }

    @Test
    void testRecordSummaryMissingFile(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("does-not-exist.zip");

        RunCommandUtil.run("record", "summary", missing.toString()).hasError().hasErrorContaining("Error reading recording summary:");
    }

    @Test
    void testRecordExtractExtractsArchive(@TempDir Path tempDir) throws Exception {
        Path recordingFile = tempDir.resolve("extract-recording.zip");
        Path outputDir = tempDir.resolve("extracted");
        createRecording(recordingFile);

        RunCommandUtil.run("record", "extract", recordingFile.toString(), outputDir.toString())
                        .hasNoError().hasOutputContaining("Extracted");

        assertTrue(Files.exists(outputDir.resolve("README.md")), "README should be extracted");
        assertTrue(Files.exists(outputDir.resolve("metadata.json")), "metadata.json should be extracted");
        assertTrue(Files.exists(outputDir.resolve("12345/thread-dumps/000-1700000000000.txt")),
            "Thread dump should be extracted");
    }

    @Test
    void testRecordExtractRejectsZipSlip(@TempDir Path tempDir) throws Exception {
        Path maliciousZip = tempDir.resolve("malicious.zip");
        Path outputDir = tempDir.resolve("out");
        Path escaped = tempDir.resolve("escaped.txt");

        try (var zipOut = new java.util.zip.ZipOutputStream(Files.newOutputStream(maliciousZip))) {
            zipOut.putNextEntry(new java.util.zip.ZipEntry("../escaped.txt"));
            zipOut.write("evil".getBytes());
            zipOut.closeEntry();
        }

        RunCommandUtil.run("record", "extract", maliciousZip.toString(), outputDir.toString()).hasError();
    }

    @Test
    void testRecordCreateFailsEarlyForMissingPid(@TempDir Path tempDir) {
        Path outputZip = tempDir.resolve("missing-pid.zip");

        RunCommandUtil.run("record", "999999999", "-o", outputZip.toString()).hasError()
                .hasErrorContaining("No JVM targets found for: 999999999");

        assertFalse(Files.exists(outputZip), "Should not create output ZIP when no target is recordable");
    }

    private static void createRecording(Path outputFile) throws Exception {
        new RecordingTestBuilder(Main.VERSION)
            .withJvm(12345, "com.example.App")
                .withThreadDump("\"main\" #1\n", 1700000000000L)
                .withSystemProperties("java.version=21\n", 1700000001000L)
                .build()
            .build(outputFile);
    }
}