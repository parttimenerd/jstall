package me.bechberger.jstall.provider;

import me.bechberger.jstall.model.SystemEnvironment;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
import me.bechberger.jstall.provider.requirement.CollectionSchedule;
import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jstall.provider.requirement.JcmdRequirement;
import me.bechberger.jstall.provider.requirement.SystemEnvironmentRequirement;
import me.bechberger.jstall.util.JVMDiscovery;
import me.bechberger.jstall.util.JcmdOutputParsers;
import me.bechberger.jstall.util.json.JsonParser;
import me.bechberger.jstall.util.json.JsonValue;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.parser.ThreadDumpParser;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Enumeration;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

/**
 * ThreadDumpProvider that replays data from a recording ZIP file.
 */
public class ReplayProvider implements ThreadDumpProvider {

    private static final String THREAD_PRINT_COMMAND = "Thread.print";
    private static final String VM_SYSTEM_PROPERTIES_COMMAND = "VM.system_properties";

    private final Path recordingZip;
    private final JsonValue.JsonObject metadata;
    private final String rootPath;

    public ReplayProvider(Path recordingZip) throws IOException {
        this.recordingZip = recordingZip;
        this.metadata = RecordingProvider.loadMetadata(recordingZip);
        this.rootPath = detectRootPath(recordingZip);
    }

    public JsonValue.JsonObject metadata() {
        return metadata;
    }

    public List<JVMDiscovery.JVMProcess> listRecordedJvms(String filter) {
        JsonValue jvmsValue = metadata.fields().get("jvms");
        if (jvmsValue == null || !jvmsValue.isArray()) {
            return List.of();
        }

        boolean hasFilter = filter != null && !filter.isBlank();
        String lowerFilter = hasFilter ? filter.toLowerCase() : null;

        List<JVMDiscovery.JVMProcess> result = new ArrayList<>();
        for (JsonValue item : jvmsValue.asArray().elements()) {
            JsonValue.JsonObject jvm = item.asObject();
            long pid = jvm.get("pid").asLong();
            String mainClass = jvm.get("mainClass").asString();

            if (!hasFilter || mainClass.toLowerCase().contains(lowerFilter)) {
                result.add(new JVMDiscovery.JVMProcess(pid, mainClass));
            }
        }

        result.sort(Comparator.comparingLong(JVMDiscovery.JVMProcess::pid));
        return result;
    }

    public boolean hasPid(long pid) {
        return listRecordedJvms(null).stream().anyMatch(jvm -> jvm.pid() == pid);
    }

    @Override
    public List<ThreadDumpSnapshot> collectFromJVM(long pid,
                                                   int count,
                                                   long intervalMs,
                                                   Path persistTo) throws IOException {
        List<ThreadDumpSnapshot> snapshots = loadForPid(pid);
        if (snapshots.isEmpty()) {
            throw new IOException("No recorded thread dumps found for PID " + pid);
        }

        int effectiveCount = count <= 0 ? snapshots.size() : Math.min(count, snapshots.size());
        return snapshots.subList(0, effectiveCount);
    }

    @Override
    public List<ThreadDumpSnapshot> loadFromFiles(List<Path> dumpFiles) throws IOException {
        return new JThreadDumpProvider().loadFromFiles(dumpFiles);
    }

    public List<ThreadDumpSnapshot> loadForPid(long pid) throws IOException {
        String pidPath = rootPath + pid + "/";

        JcmdRequirement threadDumpRequirement =
            new JcmdRequirement(THREAD_PRINT_COMMAND, null, CollectionSchedule.once());
        JcmdRequirement systemPropertiesRequirement =
            new JcmdRequirement(VM_SYSTEM_PROPERTIES_COMMAND, null, CollectionSchedule.once());
        SystemEnvironmentRequirement systemEnvironmentRequirement =
            new SystemEnvironmentRequirement(CollectionSchedule.once());

        List<CollectedData> threadDumpData;
        List<CollectedData> systemPropertiesData;
        List<CollectedData> systemEnvironmentData;

        try (ZipFile zipFile = new ZipFile(recordingZip.toFile())) {
            threadDumpData = threadDumpRequirement.load(zipFile, pidPath);
            systemPropertiesData = systemPropertiesRequirement.load(zipFile, pidPath);
            systemEnvironmentData = systemEnvironmentRequirement.load(zipFile, pidPath);
        }

        if (threadDumpData.isEmpty()) {
            throw new IOException("No thread dumps found for PID " + pid + " in recording");
        }

        List<ThreadDumpSnapshot> snapshots = new ArrayList<>(threadDumpData.size());
        for (CollectedData threadSample : threadDumpData) {
            ThreadDump parsed = ThreadDumpParser.parse(threadSample.rawData());

            CollectedData propsSample = nearestSample(systemPropertiesData, threadSample.timestamp());
            CollectedData envSample = nearestSample(systemEnvironmentData, threadSample.timestamp());

            Map<String, String> systemProperties = propsSample == null
                ? null
                : JcmdOutputParsers.parseVmSystemProperties(propsSample.rawData());

            SystemEnvironment environment = envSample == null ? null : parseSystemEnvironment(envSample.rawData());

            snapshots.add(new ThreadDumpSnapshot(parsed, threadSample.rawData(), environment, systemProperties));
        }

        snapshots.sort(Comparator.comparing(s -> s.parsed().timestamp()));
        return snapshots;
    }

    /**
     * Loads all recorded data for a PID and groups it by requirement type.
     * The requirement type is inferred from the first path segment below {@code <pid>/}.
     */
    public Map<String, List<CollectedData>> loadCollectedDataByTypeForPid(long pid) throws IOException {
        String pidPrefix = rootPath + pid + "/";
        Map<String, List<CollectedDataWithName>> grouped = new HashMap<>();

        try (ZipFile zipFile = new ZipFile(recordingZip.toFile())) {
            zipFile.stream()
                .filter(entry -> !entry.isDirectory())
                .map(entry -> entry.getName())
                .filter(name -> name.startsWith(pidPrefix))
                .filter(name -> !name.equals(pidPrefix + "manifest.json"))
                .forEach(name -> {
                    String remaining = name.substring(pidPrefix.length());
                    int slashIndex = remaining.indexOf('/');
                    if (slashIndex <= 0 || slashIndex >= remaining.length() - 1) {
                        return;
                    }

                    String type = remaining.substring(0, slashIndex);
                    String fileName = remaining.substring(slashIndex + 1);

                    try {
                        String content = new String(
                            zipFile.getInputStream(zipFile.getEntry(name)).readAllBytes(),
                            StandardCharsets.UTF_8
                        );
                        long timestamp = parseTimestampFromFileName(fileName);
                        grouped.computeIfAbsent(type, __ -> new ArrayList<>())
                            .add(new CollectedDataWithName(fileName, new CollectedData(timestamp, content, Map.of())));
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to load replay entry: " + name, e);
                    }
                });
        }

        Map<String, List<CollectedData>> byType = new HashMap<>();
        for (Map.Entry<String, List<CollectedDataWithName>> entry : grouped.entrySet()) {
            List<CollectedData> sorted = entry.getValue().stream()
                .sorted((left, right) -> {
                    int byTimestamp = Long.compare(left.data.timestamp(), right.data.timestamp());
                    if (byTimestamp != 0) {
                        return byTimestamp;
                    }
                    return left.fileName.compareTo(right.fileName);
                })
                .map(CollectedDataWithName::data)
                .collect(Collectors.toList());
            byType.put(entry.getKey(), sorted);
        }
        return byType;
    }

    private String detectRootPath(Path zipPath) throws IOException {
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            if (zipFile.getEntry("metadata.json") != null) {
                return "";
            }
            Enumeration<? extends java.util.zip.ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory() && name.endsWith("/metadata.json")) {
                    return name.substring(0, name.length() - "metadata.json".length());
                }
            }
            return "";
        }
    }

    private long parseTimestampFromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return 0L;
        }
        int dashIndex = fileName.indexOf('-');
        int dotIndex = fileName.lastIndexOf('.');
        if (dashIndex < 0 || dotIndex <= dashIndex + 1) {
            return 0L;
        }
        try {
            return Long.parseLong(fileName.substring(dashIndex + 1, dotIndex));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private record CollectedDataWithName(String fileName, CollectedData data) {}

    private CollectedData nearestSample(List<CollectedData> samples, long timestamp) {
        if (samples == null || samples.isEmpty()) {
            return null;
        }

        return samples.stream()
            .min(Comparator.comparingLong(s -> Math.abs(s.timestamp() - timestamp)))
            .orElse(null);
    }

    private SystemEnvironment parseSystemEnvironment(String json) {
        JsonValue.JsonObject root = JsonParser.parse(json).asObject();
        JsonValue processesValue = root.get("processes");
        if (processesValue == null || !processesValue.isArray()) {
            return new SystemEnvironment(List.of());
        }

        List<SystemEnvironment.Process> processes = new ArrayList<>();
        for (JsonValue processValue : processesValue.asArray().elements()) {
            JsonValue.JsonObject process = processValue.asObject();
            long pid = process.get("pid").asLong();
            String command = process.get("command").asString();

            Duration cpuTime = null;
            if (process.has("cpuTimeNanos")) {
                cpuTime = Duration.ofNanos(process.get("cpuTimeNanos").asLong());
            }

            processes.add(new SystemEnvironment.Process(pid, null, cpuTime, command));
        }

        return new SystemEnvironment(processes);
    }

    public void printReplayTargets(PrintStream out) {
        List<JVMDiscovery.JVMProcess> jvms = listRecordedJvms(null);
        if (jvms.isEmpty()) {
            out.println("No recorded JVMs found in replay file.");
            return;
        }
        System.out.println("Recorded JVMs:");
        for (JVMDiscovery.JVMProcess jvm : jvms) {
            out.println("  " + jvm);
        }
    }

    /**
     * Gets the flamegraph HTML content for the specified PID.
     * Returns null if no flamegraph is available.
     * Flamegraph is always at: <pid>/flamegraphs/flame.html
     */
    public FlamegraphData getFlamegraph(long pid) throws IOException {
        String flamePath = rootPath + pid + "/flamegraphs/flame.html";
        String metaPath = rootPath + pid + "/flamegraphs/flame.meta.json";

        try (ZipFile zipFile = new ZipFile(recordingZip.toFile())) {
            var flameEntry = zipFile.getEntry(flamePath);
            if (flameEntry == null) {
                return null;
            }

            String html = new String(
                zipFile.getInputStream(flameEntry).readAllBytes(),
                StandardCharsets.UTF_8
            );

            long timestamp = System.currentTimeMillis(); // Timestamp from when extracted

            // Load metadata from .meta.json file
            Map<String, String> profilingMetadata = new HashMap<>();
            var metaEntry = zipFile.getEntry(metaPath);
            if (metaEntry != null) {
                String metaJson = new String(
                    zipFile.getInputStream(metaEntry).readAllBytes(),
                    StandardCharsets.UTF_8
                );
                profilingMetadata = parseMetadataJson(metaJson);
            }

            return new FlamegraphData(html, timestamp, profilingMetadata);
        }
    }

    /**
     * Parses the metadata JSON file.
     */
    private Map<String, String> parseMetadataJson(String json) {
        Map<String, String> metadata = new HashMap<>();
        if (json == null || json.isBlank()) {
            return metadata;
        }

        // Simple JSON parsing for our metadata format: {"key":"value",...}
        json = json.trim();
        if (json.startsWith("{") && json.endsWith("}")) {
            json = json.substring(1, json.length() - 1);
            String[] pairs = json.split(",");
            for (String pair : pairs) {
                String[] kv = pair.split(":", 2);
                if (kv.length == 2) {
                    String key = kv[0].trim().replace("\"", "");
                    String value = kv[1].trim().replace("\"", "");
                    metadata.put(key, value);
                }
            }
        }

        return metadata;
    }

    /**
     * Gets the JFR file content for the specified PID.
     * Returns null if no JFR file is available.
     * JFR file is always at: <pid>/jfr/default.jfr
     */
    public JfrData getJfrFile(long pid) throws IOException {
        String jfrPath = rootPath + pid + "/jfr/default.jfr";

        try (ZipFile zipFile = new ZipFile(recordingZip.toFile())) {
            var jfrEntry = zipFile.getEntry(jfrPath);
            if (jfrEntry == null) {
                return null;
            }

            byte[] jfrBytes = zipFile.getInputStream(jfrEntry).readAllBytes();
            long timestamp = System.currentTimeMillis(); // Timestamp from when extracted

            return new JfrData(jfrBytes, timestamp);
        }
    }

    /**
     * Data class for flamegraph content and metadata.
     */
    public record FlamegraphData(String htmlContent, long timestamp, Map<String, String> metadata) {
        /**
         * Writes the flamegraph HTML to the specified file.
         */
        public void writeTo(Path outputPath) throws IOException {
            java.nio.file.Files.writeString(outputPath, htmlContent, StandardCharsets.UTF_8);
        }

        /**
         * Gets a metadata value, or null if not present.
         */
        public String getMetadata(String key) {
            return metadata.get(key);
        }

        /**
         * Gets the profiling event type (cpu, alloc, lock, wall), or "unknown" if not available.
         */
        public String getEvent() {
            return metadata.getOrDefault("event", "unknown");
        }

        /**
         * Gets the profiling duration (windowMs formatted as human-readable string).
         */
        public String getDuration() {
            String windowMs = metadata.get("windowMs");
            if (windowMs != null) {
                try {
                    long ms = Long.parseLong(windowMs);
                    if (ms >= 1000) {
                        return (ms / 1000.0) + "s";
                    } else {
                        return ms + "ms";
                    }
                } catch (NumberFormatException e) {
                    return windowMs;
                }
            }
            return metadata.get("duration");
        }

        /**
         * Gets the sampling interval (intervalNanos formatted as human-readable string).
         */
        public String getInterval() {
            String intervalNanos = metadata.get("intervalNanos");
            if (intervalNanos != null) {
                try {
                    long nanos = Long.parseLong(intervalNanos);
                    if (nanos >= 1_000_000) {
                        return (nanos / 1_000_000.0) + "ms";
                    } else if (nanos >= 1_000) {
                        return (nanos / 1_000.0) + "µs";
                    } else {
                        return nanos + "ns";
                    }
                } catch (NumberFormatException e) {
                    return intervalNanos;
                }
            }
            return metadata.get("interval");
        }
    }

    /**
     * Data class for JFR file content and metadata.
     */
    public record JfrData(byte[] content, long timestamp) {
        /**
         * Writes the JFR content to the specified file.
         */
        public void writeTo(Path outputPath) throws IOException {
            java.nio.file.Files.write(outputPath, content);
        }
    }
}