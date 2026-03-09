package me.bechberger.jstall.provider;

import me.bechberger.jstall.util.JVMDiscovery;
import me.bechberger.jstall.util.json.JsonPrinter;
import me.bechberger.jstall.util.json.JsonValue;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Test utility for building recording ZIP files without needing actual JVMs.
 * Allows easy creation of replay files for unit testing.
 */
public class RecordingTestBuilder {

    private final String version;
    private final List<JvmRecording> jvms = new ArrayList<>();
    private long createdAt = System.currentTimeMillis();

    public RecordingTestBuilder(String version) {
        this.version = version;
    }

    public RecordingTestBuilder createdAt(long timestamp) {
        this.createdAt = timestamp;
        return this;
    }

    /**
     * Adds a JVM recording with thread dumps and optional collected data.
     */
    public JvmRecordingBuilder withJvm(long pid, String mainClass) {
        return new JvmRecordingBuilder(this, pid, mainClass);
    }

    /**
     * Builds the recording ZIP file.
     */
    public void build(Path outputFile) throws IOException {
        Files.createDirectories(outputFile.getParent());
        String rootPath = rootPathFromOutput(outputFile);

        try (ZipOutputStream zipOut = new ZipOutputStream(
                Files.newOutputStream(outputFile))) {
            writeMetadata(zipOut, rootPath);
            writeReadme(zipOut, rootPath);
            for (JvmRecording jvm : jvms) {
                writeJvmData(zipOut, rootPath, jvm);
            }
        }
    }

    private String rootPathFromOutput(Path outputFile) {
        String fileName = outputFile.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        if (base.isBlank()) {
            base = "recording";
        }
        return base + "/";
    }

    private void writeMetadata(ZipOutputStream zipOut, String rootPath) throws IOException {
        Map<String, JsonValue> metadata = new LinkedHashMap<>();
        metadata.put("format_version", new JsonValue.JsonNumber(RecordingProvider.FORMAT_VERSION));
        metadata.put("version", new JsonValue.JsonString(version));
        metadata.put("created_at", new JsonValue.JsonNumber(createdAt));

        List<JsonValue> jvmsList = new ArrayList<>();
        for (JvmRecording jvm : jvms) {
            Map<String, JsonValue> jvmEntry = new LinkedHashMap<>();
            jvmEntry.put("pid", new JsonValue.JsonNumber(jvm.pid));
            jvmEntry.put("mainClass", new JsonValue.JsonString(jvm.mainClass));
            jvmEntry.put("success", new JsonValue.JsonBoolean(jvm.successful));
            jvmEntry.put("started_at", new JsonValue.JsonNumber(jvm.startedAt));
            jvmEntry.put("finished_at", new JsonValue.JsonNumber(jvm.finishedAt));
            jvmsList.add(new JsonValue.JsonObject(jvmEntry));
        }
        metadata.put("jvms", new JsonValue.JsonArray(jvmsList));

        JsonValue.JsonObject metadataObj = new JsonValue.JsonObject(metadata);
        String json = JsonPrinter.print(metadataObj);

        ZipEntry entry = new ZipEntry(rootPath + "metadata.json");
        zipOut.putNextEntry(entry);
        zipOut.write(json.getBytes(StandardCharsets.UTF_8));
        zipOut.closeEntry();
    }

    private void writeReadme(ZipOutputStream zipOut, String rootPath) throws IOException {
        StringBuilder content = new StringBuilder();
        content.append("JStall Recording Archive\n");
        content.append("========================\n\n");
        content.append("Created by jstall record.\n");
        content.append("Project: https://github.com/parttimenerd/jstall\n\n");
        content.append("Recorded JVMs:\n");
        for (JvmRecording jvm : jvms) {
            content.append("- ").append(jvm.pid);
            content.append(": ").append(jvm.mainClass);
            if (!jvm.successful) {
                content.append(" [FAILED]");
            }
            content.append("\n  → See folder: ").append(jvm.pid).append("/\n");
        }
        writeZipEntry(zipOut, rootPath + "README.md", content.toString());
    }

    private void writeJvmData(ZipOutputStream zipOut, String rootPath, JvmRecording jvm) throws IOException {
        // Write thread dumps
        for (int i = 0; i < jvm.threadDumps.size(); i++) {
            String fileName = String.format("%s%d/thread-dumps/%03d-%d.txt",
                rootPath,
                jvm.pid, i, jvm.threadDumpTimestamps.get(i));
            writeZipEntry(zipOut, fileName, jvm.threadDumps.get(i));
        }

        // Write system properties if collected
        for (int i = 0; i < jvm.systemProperties.size(); i++) {
            String fileName = String.format("%s%d/system-properties/%03d-%d.txt",
                rootPath,
                jvm.pid, i, jvm.propTimestamps.get(i));
            writeZipEntry(zipOut, fileName, jvm.systemProperties.get(i));
        }

        // Write system environment if collected
        for (int i = 0; i < jvm.systemEnvironments.size(); i++) {
            String fileName = String.format("%s%d/system-environment/%03d-%d.json",
                rootPath,
                jvm.pid, i, jvm.envTimestamps.get(i));
            writeZipEntry(zipOut, fileName, jvm.systemEnvironments.get(i));
        }

        // Write other collected data
        for (Map.Entry<String, List<String>> entry : jvm.otherData.entrySet()) {
            String dataType = entry.getKey();
            List<String> samples = entry.getValue();
            for (int i = 0; i < samples.size(); i++) {
                long timestamp = jvm.otherDataTimestamps.getOrDefault(
                    dataType + ":" + i, System.currentTimeMillis() + i * 1000);
                String fileName = String.format("%s%d/%s/%03d-%d.txt",
                    rootPath,
                    jvm.pid, dataType, i, timestamp);
                writeZipEntry(zipOut, fileName, samples.get(i));
            }
        }
    }

    private void writeZipEntry(ZipOutputStream zipOut, String name, String content)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zipOut.putNextEntry(entry);
        zipOut.write(content.getBytes(StandardCharsets.UTF_8));
        zipOut.closeEntry();
    }

    void addJvmRecording(JvmRecording recording) {
        jvms.add(recording);
    }

    /**
     * Builder for a single JVM recording within the archive.
     */
    public static class JvmRecordingBuilder {
        private final RecordingTestBuilder parent;
        private final long pid;
        private final String mainClass;
        private final List<String> threadDumps = new ArrayList<>();
        private final List<Long> threadDumpTimestamps = new ArrayList<>();
        private final List<String> systemProperties = new ArrayList<>();
        private final List<Long> propTimestamps = new ArrayList<>();
        private final List<String> systemEnvironments = new ArrayList<>();
        private final List<Long> envTimestamps = new ArrayList<>();
        private final Map<String, List<String>> otherData = new LinkedHashMap<>();
        private final Map<String, Long> otherDataTimestamps = new LinkedHashMap<>();
        private long startedAt = System.currentTimeMillis();
        private long finishedAt;
        private boolean successful = true;

        JvmRecordingBuilder(RecordingTestBuilder parent, long pid, String mainClass) {
            this.parent = parent;
            this.pid = pid;
            this.mainClass = mainClass;
        }

        public JvmRecordingBuilder withThreadDump(String content, long timestamp) {
            threadDumps.add(content);
            threadDumpTimestamps.add(timestamp);
            return this;
        }

        public JvmRecordingBuilder withSystemProperties(String content, long timestamp) {
            systemProperties.add(content);
            propTimestamps.add(timestamp);
            return this;
        }

        public JvmRecordingBuilder withSystemEnvironment(String jsonContent, long timestamp) {
            systemEnvironments.add(jsonContent);
            envTimestamps.add(timestamp);
            return this;
        }

        public JvmRecordingBuilder withCustomData(String dataType, String content, long timestamp) {
            otherData.computeIfAbsent(dataType, k -> new ArrayList<>()).add(content);
            otherDataTimestamps.put(dataType + ":" + (otherData.get(dataType).size() - 1), timestamp);
            return this;
        }

        public JvmRecordingBuilder finishedAt(long timestamp) {
            this.finishedAt = timestamp;
            return this;
        }

        public JvmRecordingBuilder failed() {
            this.successful = false;
            return this;
        }

        public RecordingTestBuilder build() {
            if (finishedAt == 0) {
                finishedAt = System.currentTimeMillis();
            }
            parent.addJvmRecording(new JvmRecording(
                pid, mainClass, successful, startedAt, finishedAt,
                threadDumps, threadDumpTimestamps,
                systemProperties, propTimestamps,
                systemEnvironments, envTimestamps,
                otherData, otherDataTimestamps));
            return parent;
        }
    }

    private static class JvmRecording {
        final long pid;
        final String mainClass;
        final boolean successful;
        final long startedAt;
        final long finishedAt;
        final List<String> threadDumps;
        final List<Long> threadDumpTimestamps;
        final List<String> systemProperties;
        final List<Long> propTimestamps;
        final List<String> systemEnvironments;
        final List<Long> envTimestamps;
        final Map<String, List<String>> otherData;
        final Map<String, Long> otherDataTimestamps;

        JvmRecording(long pid, String mainClass, boolean successful,
                    long startedAt, long finishedAt,
                    List<String> threadDumps, List<Long> threadDumpTimestamps,
                    List<String> systemProperties, List<Long> propTimestamps,
                    List<String> systemEnvironments, List<Long> envTimestamps,
                    Map<String, List<String>> otherData, Map<String, Long> otherDataTimestamps) {
            this.pid = pid;
            this.mainClass = mainClass;
            this.successful = successful;
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.threadDumps = threadDumps;
            this.threadDumpTimestamps = threadDumpTimestamps;
            this.systemProperties = systemProperties;
            this.propTimestamps = propTimestamps;
            this.systemEnvironments = systemEnvironments;
            this.envTimestamps = envTimestamps;
            this.otherData = otherData;
            this.otherDataTimestamps = otherDataTimestamps;
        }
    }
}