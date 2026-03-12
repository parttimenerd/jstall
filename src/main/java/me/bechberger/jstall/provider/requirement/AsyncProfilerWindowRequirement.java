package me.bechberger.jstall.provider.requirement;

import me.bechberger.jstall.util.JMXDiagnosticHelper;
import me.bechberger.jstall.util.JcmdOutputParsers;
import me.bechberger.jstall.util.JvmVersionChecker;
import one.profiler.AsyncProfilerLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Async Profiler and JFR recording requirement */
public class AsyncProfilerWindowRequirement implements IntervalWindowRequirement {

    private static final String TYPE = "profiling-windows";
    private static final String FLAME_SUBDIR = "flamegraphs/";
    private static final String JFR_SUBDIR = "jfr/";
    private static final long PROFILE_SAMPLE_INTERVAL_NANOS = 10_000_000L;
    private static final long MIN_WINDOW_MS = 500;

    private final CollectionSchedule schedule;
    private final String event;

    public AsyncProfilerWindowRequirement(CollectionSchedule schedule) {
        this(schedule, "cpu");
    }

    public AsyncProfilerWindowRequirement(CollectionSchedule schedule, String event) {
        this.schedule = schedule;
        this.event = event;
    }

    public static AsyncProfilerWindowRequirement forSampling(int count, long intervalMs) {
        int windows = Math.max(0, count - 1);
        return new AsyncProfilerWindowRequirement(CollectionSchedule.intervals(windows, intervalMs));
    }

    public static boolean isPlatformSupported() {
        return AsyncProfilerLoader.isSupported();
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public CollectionSchedule getSchedule() {
        return schedule;
    }

    public String getEvent() {
        return event;
    }

    @Override
    public CollectedData collect(JMXDiagnosticHelper helper, int sampleIndex) throws IOException {
        long defaultWindow = Math.max(0, schedule.intervalMs() - 200);
        return collectWindow(helper, sampleIndex, defaultWindow);
    }

    @Override
    public CollectedData collectWindow(JMXDiagnosticHelper helper, int sampleIndex, long windowMs) {
        long timestamp = System.currentTimeMillis();
        if (!isPlatformSupported()) {
            return skip(timestamp, "platform-not-supported");
        }
        if (windowMs < MIN_WINDOW_MS) {
            return skip(timestamp, "window-too-short");
        }
        if (!isCurrentJvmRelease(helper)) {
            return skip(timestamp, "jvm-not-current-release");
        }

        long pid = helper.pid();
        Path flamePath;
        Path jfrPath;
        try {
            flamePath = Files.createTempFile("jstall-flame-", ".html");
            jfrPath = Files.createTempFile("jstall-record-", ".jfr");
        } catch (IOException e) {
            return skip(timestamp, "temp-file-create-failed");
        }

        String recordingName = "jstall-record-" + pid + "-" + sampleIndex + "-" + timestamp;
        boolean jfrStarted = startJfr(pid, recordingName);
        try {
            AsyncProfilerLoader.executeProfiler("-d", windowMs / 1000.0 + "s",
                    "-e", event,
                    "-i", String.valueOf(PROFILE_SAMPLE_INTERVAL_NANOS),
                    "-f", flamePath.toString(),
                    String.valueOf(pid));

            if (!Files.exists(flamePath)) {
                return skip(timestamp, "flamegraph-capture-failed");
            }

            if (jfrStarted) {
                dumpJfr(pid, recordingName, jfrPath);
            }

            String flameHtml = Files.readString(flamePath, StandardCharsets.UTF_8);
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("windowMs", String.valueOf(windowMs));
            metadata.put("event", event);
            if (Files.exists(jfrPath)) {
                byte[] jfrBytes = Files.readAllBytes(jfrPath);
                if (jfrBytes.length > 0) {
                    metadata.put("jfrBase64", Base64.getEncoder().encodeToString(jfrBytes));
                }
            }
            return new CollectedData(timestamp, flameHtml, metadata);
        } catch (Exception e) {
            return skip(timestamp, "profiling-failed");
        } finally {
            if (jfrStarted) {
                stopJfr(pid, recordingName);
            }
            try {
                Files.deleteIfExists(flamePath);
                Files.deleteIfExists(jfrPath);
            } catch (IOException ignored) {
            }
        }
    }

    private CollectedData skip(long timestamp, String reason) {
        return new CollectedData(timestamp, "", Map.of("skip", "true", "reason", reason));
    }

    private boolean isCurrentJvmRelease(JMXDiagnosticHelper helper) {
        try {
            Map<String, String> props = JcmdOutputParsers.parseVmSystemProperties(helper.getSystemProperties());
            String date = props.get("java.version.date");
            return JvmVersionChecker.isCurrentRelease(date, Clock.systemDefaultZone());
        } catch (IOException e) {
            return false;
        }
    }

    private boolean startJfr(long pid, String recordingName) {
        try {
            runJcmd(pid, "JFR.start", "name=" + recordingName, "settings=profile");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void dumpJfr(long pid, String recordingName, Path jfrPath) {
        try {
            runJcmd(pid, "JFR.dump", "name=" + recordingName, "filename=" + jfrPath);
        } catch (IOException ignored) {
        }
    }

    private void stopJfr(long pid, String recordingName) {
        try {
            runJcmd(pid, "JFR.stop", "name=" + recordingName);
        } catch (IOException ignored) {
        }
    }

    private void runJcmd(long pid, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("jcmd");
        cmd.add(String.valueOf(pid));
        cmd.addAll(Arrays.asList(args));

        Process process = new ProcessBuilder(cmd).start();
        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException("jcmd failed: " + err);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running jcmd", e);
        }
    }

    @Override
    public void persist(ZipOutputStream zipOut, String pidPath, List<CollectedData> samples) throws IOException {
        // Only persist the first valid sample (there should be only one)
        for (CollectedData sample : samples) {
            if (sample.metadata().containsKey("skip") || sample.rawData().isBlank()) {
                continue;
            }

            // Write flamegraph HTML as flame.html
            String flameEntry = pidPath + FLAME_SUBDIR + "flame.html";
            ZipEntry flameZipEntry = new ZipEntry(flameEntry);
            zipOut.putNextEntry(flameZipEntry);
            zipOut.write(sample.rawData().getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();

            // Write metadata JSON as flame.meta.json (event, windowMs, interval)
            String metaEntry = pidPath + FLAME_SUBDIR + "flame.meta.json";
            ZipEntry metaZipEntry = new ZipEntry(metaEntry);
            zipOut.putNextEntry(metaZipEntry);

            StringBuilder metaJson = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, String> entry : sample.metadata().entrySet()) {
                if (!entry.getKey().equals("jfrBase64")) { // Don't include base64 data in metadata
                    if (!first) metaJson.append(",");
                    metaJson.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
                    first = false;
                }
            }
            // Add interval info (PROFILE_SAMPLE_INTERVAL_NANOS)
            if (!sample.metadata().isEmpty()) {
                metaJson.append(",\"intervalNanos\":\"").append(PROFILE_SAMPLE_INTERVAL_NANOS).append("\"");
            }
            metaJson.append("}");

            zipOut.write(metaJson.toString().getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();

            // Write JFR file as default.jfr if available
            String jfrBase64 = sample.metadata().get("jfrBase64");
            if (jfrBase64 != null && !jfrBase64.isBlank()) {
                String jfrEntry = pidPath + JFR_SUBDIR + "default.jfr";
                ZipEntry jfrZipEntry = new ZipEntry(jfrEntry);
                zipOut.putNextEntry(jfrZipEntry);
                zipOut.write(Base64.getDecoder().decode(jfrBase64));
                zipOut.closeEntry();
            }

            // Only persist the first valid sample
            break;
        }
    }

    @Override
    public List<CollectedData> load(ZipFile zipFile, String pidPath) throws IOException {
        List<CollectedData> result = new ArrayList<>();
        String flamePath = pidPath + FLAME_SUBDIR + "flame.html";

        var entry = zipFile.getEntry(flamePath);
        if (entry != null) {
            try {
                String html = new String(zipFile.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                result.add(new CollectedData(System.currentTimeMillis(), html, Map.of()));
            } catch (Exception e) {
                throw new RuntimeException("Failed to load flamegraph: " + flamePath, e);
            }
        }
        return result;
    }

    @Override
    public String getDirectoryDescription() {
        return "async-profiler flamegraphs and JFR recordings (if supported)";
    }

    @Override
    public List<String> getExpectedFiles(List<CollectedData> samples) {
        if (samples == null || samples.isEmpty()) {
            return List.of();
        }
        List<String> files = new ArrayList<>();
        for (CollectedData sample : samples) {
            if (sample.metadata().containsKey("skip") || sample.rawData().isBlank()) {
                continue;
            }
            files.add(FLAME_SUBDIR + "flame.html");
            files.add(FLAME_SUBDIR + "flame.meta.json");
            if (sample.metadata().containsKey("jfrBase64") && !sample.metadata().get("jfrBase64").isBlank()) {
                files.add(JFR_SUBDIR + "default.jfr");
            }
            break; // only one valid sample is persisted
        }
        return files;
    }
}