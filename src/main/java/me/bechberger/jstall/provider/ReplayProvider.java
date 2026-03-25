package me.bechberger.jstall.provider;

import me.bechberger.jstall.model.SystemEnvironment;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
import me.bechberger.jstall.provider.requirement.CollectionSchedule;
import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jstall.provider.requirement.JcmdRequirement;
import me.bechberger.jstall.provider.requirement.SystemEnvironmentRequirement;
import me.bechberger.jstall.util.JsonValueUtils;
import me.bechberger.jstall.util.JVMDiscovery;
import me.bechberger.jstall.util.JcmdOutputParsers;
import me.bechberger.util.json.JSONParser;
import me.bechberger.util.json.Util;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.parser.ThreadDumpParser;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ThreadDumpProvider that replays data from a recording ZIP file.
 */
public class ReplayProvider {

    private static final String THREAD_PRINT_COMMAND = "Thread.print";
    private static final String VM_SYSTEM_PROPERTIES_COMMAND = "VM.system_properties";

    private final Path recordingZip;
    private final Map<String, Object> metadata;
    private final String rootPath;

    public ReplayProvider(Path recordingZip) throws IOException {
        this.recordingZip = recordingZip;
        try (ZipFile zipFile = new ZipFile(recordingZip.toFile())) {
            this.rootPath = detectRootPath(zipFile);
            this.metadata = loadMetadata(zipFile, rootPath);
        }
    }

    public static Map<String, Object> loadMetadata(Path recordingZip) throws IOException {
        try (ZipFile zipFile = new ZipFile(recordingZip.toFile())) {
            return loadMetadata(zipFile, detectRootPath(zipFile));
        }
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public String rootPath() {
        return rootPath;
    }

    public String readUtf8(String relativePath) throws IOException {
        try (ZipFile zipFile = new ZipFile(recordingZip.toFile())) {
            return readUtf8(zipFile, relativePath);
        }
    }

    public byte[] readBytes(String relativePath) throws IOException {
        try (ZipFile zipFile = new ZipFile(recordingZip.toFile())) {
            ZipEntry entry = findEntry(zipFile, relativePath)
                .orElseThrow(() -> new IOException("Recording is missing " + relativePath));
            return zipFile.getInputStream(entry).readAllBytes();
        }
    }

    public String readReadme() throws IOException {
        return readUtf8("README.md");
    }

    public List<JVMDiscovery.JVMProcess> listRecordedJvms(String filter) {
        Object jvmsValue = metadata.get("jvms");
        if (!(jvmsValue instanceof List<?> items)) {
            return List.of();
        }

        boolean hasFilter = filter != null && !filter.isBlank();
        String lowerFilter = hasFilter ? filter.toLowerCase() : null;

        List<JVMDiscovery.JVMProcess> result = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> jvm = Util.asMap(item);
            long pid = JsonValueUtils.asLong(jvm.get("pid"));
            String mainClass = JsonValueUtils.asString(jvm.get("mainClass"));

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
                .map(ZipEntry::getName)
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
                .sorted(Comparator.comparingLong((CollectedDataWithName left) -> left.data.timestamp()).thenComparing(left -> left.fileName))
                .map(CollectedDataWithName::data)
                .collect(Collectors.toList());
            byType.put(entry.getKey(), sorted);
        }

        Map<String, Object> jvmMetadata = findJvmMetadata(pid);
        if (jvmMetadata != null) {
            String vmUptime = getOptionalString(jvmMetadata, "vmUptime");
            if (vmUptime != null && !vmUptime.isBlank()) {
                long ts = getOptionalLong(jvmMetadata, "finishedAt", getOptionalLong(jvmMetadata, "finished_at", 0L));
                byType.computeIfAbsent("vm-uptime", __ -> new ArrayList<>())
                    .add(new CollectedData(ts, vmUptime, Map.of("source", "metadata.json")));
            }
        }

        return byType;
    }

    private Map<String, Object> findJvmMetadata(long pid) {
        Object jvmsValue = metadata.get("jvms");
        if (!(jvmsValue instanceof List<?> items)) {
            return null;
        }
        for (Object item : items) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> jvm = Util.asMap(item);
            if (jvm.get("pid") instanceof Number pidValue && pidValue.longValue() == pid) {
                return jvm;
            }
        }
        return null;
    }

    private String getOptionalString(Map<String, Object> obj, String key) {
        Object value = obj.get(key);
        if (!(value instanceof String string)) {
            return null;
        }
        return string;
    }

    private long getOptionalLong(Map<String, Object> obj, String key, long defaultValue) {
        Object value = obj.get(key);
        if (!(value instanceof Number number)) {
            return defaultValue;
        }
        return number.longValue();
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
        Map<String, Object> root;
        try {
            root = Util.asMap(JSONParser.parse(json));
        } catch (Exception e) {
            return new SystemEnvironment(List.of());
        }
        Object processesValue = root.get("processes");
        if (!(processesValue instanceof List<?>)) {
            return new SystemEnvironment(List.of());
        }
        List<Object> processValues = Util.asList(processesValue);

        List<SystemEnvironment.Process> processes = new ArrayList<>();
        for (Object processValue : processValues) {
            Map<String, Object> process = Util.asMap(processValue);
            long pid = JsonValueUtils.asLong(process.get("pid"));
            String command = JsonValueUtils.asString(process.get("command"));

            Duration cpuTime = null;
            if (process.get("cpuTimeNanos") instanceof Number cpuTimeNanos) {
                cpuTime = Duration.ofNanos(cpuTimeNanos.longValue());
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
        String flamePath = pid + "/flamegraphs/flame.html";
        String metaPath = pid + "/flamegraphs/flame.meta.json";

        try (ZipFile zipFile = new ZipFile(recordingZip.toFile())) {
            var flameEntry = findEntry(zipFile, flamePath).orElse(null);
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
            var metaEntry = findEntry(zipFile, metaPath).orElse(null);
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

        try {
            Map<String, Object> parsed = Util.asMap(JSONParser.parse(json));
            for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                if (entry.getValue() != null) {
                    metadata.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
        } catch (Exception ignored) {
        }

        return metadata;
    }

    /**
     * Gets the JFR file content for the specified PID.
     * Returns null if no JFR file is available.
     * JFR file is always at: <pid>/jfr/default.jfr
     */
    public JfrData getJfrFile(long pid) throws IOException {
        String jfrPath = pid + "/jfr/default.jfr";

        try (ZipFile zipFile = new ZipFile(recordingZip.toFile())) {
            var jfrEntry = findEntry(zipFile, jfrPath).orElse(null);
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

    private static Map<String, Object> loadMetadata(ZipFile zipFile, String rootPath) throws IOException {
        String content = readUtf8(zipFile, rootPath, "metadata.json");
        try {
            return Util.asMap(JSONParser.parse(content));
        } catch (RuntimeException e) {
            throw new IOException("Failed to parse recording metadata", e);
        }
    }

    private static Optional<ZipEntry> findEntry(ZipFile zipFile, String rootPath, String relativePath) {
        ZipEntry direct = zipFile.getEntry(rootPath + relativePath);
        if (direct != null && !direct.isDirectory()) {
            return Optional.of(direct);
        }
        if (!rootPath.isEmpty()) {
            ZipEntry rootless = zipFile.getEntry(relativePath);
            if (rootless != null && !rootless.isDirectory()) {
                return Optional.of(rootless);
            }
        }
        return Optional.empty();
    }

    private Optional<ZipEntry> findEntry(ZipFile zipFile, String relativePath) {
        return findEntry(zipFile, rootPath, relativePath);
    }

    private static String readUtf8(ZipFile zipFile, String rootPath, String relativePath) throws IOException {
        ZipEntry entry = findEntry(zipFile, rootPath, relativePath)
            .orElseThrow(() -> new IOException("Recording is missing " + relativePath));
        return new String(zipFile.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
    }

    private String readUtf8(ZipFile zipFile, String relativePath) throws IOException {
        return readUtf8(zipFile, rootPath, relativePath);
    }

    private static String detectRootPath(ZipFile zipFile) {
        if (zipFile.getEntry("metadata.json") != null) {
            return "";
        }

        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!entry.isDirectory() && name.endsWith("/metadata.json")) {
                return name.substring(0, name.length() - "metadata.json".length());
            }
        }
        return "";
    }
}