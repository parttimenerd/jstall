package me.bechberger.jstall.provider.requirement;

import me.bechberger.jstall.util.CommandExecutor;
import me.bechberger.jstall.util.JMXDiagnosticHelper;
import one.profiler.AsyncProfilerLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private final boolean recordJfr;
    private final CollectionSchedule schedule;
    private final String event;
    private final long intervalNanos;

    public AsyncProfilerWindowRequirement(CollectionSchedule schedule, boolean recordJfr) {
        this(schedule, "cpu", recordJfr);
    }

    public AsyncProfilerWindowRequirement(CollectionSchedule schedule, String event, boolean recordJfr) {
        this(schedule, event, recordJfr, PROFILE_SAMPLE_INTERVAL_NANOS);
    }

    public AsyncProfilerWindowRequirement(CollectionSchedule schedule, String event, boolean recordJfr, long intervalNanos) {
        this.recordJfr = recordJfr;
        this.schedule = schedule;
        this.event = event;
        this.intervalNanos = intervalNanos;
    }

    public static AsyncProfilerWindowRequirement forSampling(int count, long intervalMs) {
        int windows = Math.max(0, count - 1);
        return new AsyncProfilerWindowRequirement(CollectionSchedule.intervals(windows, intervalMs), false);
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
        if (windowMs < MIN_WINDOW_MS) {
            return skip(timestamp, "window-too-short");
        }
        return helper.getExecutor().isRemote()
                ? collectWindowRemote(helper, sampleIndex, windowMs, timestamp)
                : collectWindowLocal(helper, sampleIndex, windowMs, timestamp);
    }

    /** Local path: uses the bundled {@link AsyncProfilerLoader}. */
    private CollectedData collectWindowLocal(JMXDiagnosticHelper helper, int sampleIndex, long windowMs, long timestamp) {
        if (!isPlatformSupported()) {
            return skip(timestamp, "platform-not-supported");
        }

        long pid = helper.pid();
        Path flamePath;
        Path jfrPath = null;
        try {
            flamePath = Files.createTempFile("jstall-flame-", ".html");
            if (recordJfr) {
                jfrPath = Files.createTempFile("jstall-record-", ".jfr");
            }
        } catch (IOException e) {
            return skip(timestamp, "temp-file-create-failed");
        }

        String recordingName = "jstall-record-" + pid + "-" + sampleIndex + "-" + timestamp;
        boolean jfrStarted = recordJfr && startJfr(helper, recordingName);
        Map<String, Path> tempFiles = new LinkedHashMap<>();
        try {
            AsyncProfilerLoader.executeProfiler("-d", windowMs / 1000.0 + "s",
                    "-e", event,
                    "-i", String.valueOf(intervalNanos),
                    "-f", flamePath.toString(),
                    String.valueOf(pid));

            if (!Files.exists(flamePath)) {
                return skip(timestamp, "flamegraph-capture-failed");
            }

            if (jfrStarted) {
                dumpJfr(helper, recordingName, jfrPath);
            }

            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("windowMs", String.valueOf(windowMs));
            metadata.put("event", event);

            tempFiles.put("flame", flamePath);
            if (jfrPath != null && Files.exists(jfrPath) && Files.size(jfrPath) > 0) {
                tempFiles.put("jfr", jfrPath);
            }
            return new CollectedData(timestamp, "", metadata, Collections.unmodifiableMap(tempFiles));
        } catch (Exception e) {
            return skip(timestamp, "profiling-failed");
        } finally {
            if (jfrStarted) {
                stopJfr(helper, recordingName);
            }
            // Only delete files not handed off to CollectedData
            if (!tempFiles.containsKey("flame")) try { Files.deleteIfExists(flamePath); } catch (IOException ignored) {}
            if (!tempFiles.containsKey("jfr") && jfrPath != null) try { Files.deleteIfExists(jfrPath); } catch (IOException ignored) {}
        }
    }

    /**
     * Remote path: optionally captures a flamegraph via the {@code asprof} binary (if present)
     * and/or a JFR recording via jcmd. JFR is always attempted when {@link #recordJfr} is set,
     * regardless of whether {@code asprof} is available.
     * Remote files are transferred to local temporary files via {@link CommandExecutor.TemporaryFile#copyInto(Path)}.
     */
    private CollectedData collectWindowRemote(JMXDiagnosticHelper helper, int sampleIndex, long windowMs, long timestamp) {
        CommandExecutor executor = helper.getExecutor();

        // Probe for the asprof binary — failure is non-fatal (JFR may still be collected).
        boolean asprofAvailable;
        try {
            asprofAvailable = executor.executeCommand("asprof", "-v").exitCode() == 0;
        } catch (IOException e) {
            asprofAvailable = false;
        }

        long pid = helper.pid();
        String recordingName = "jstall-record-" + pid + "-" + sampleIndex + "-" + timestamp;
        boolean jfrStarted = recordJfr && startJfr(helper, recordingName);

        CommandExecutor.TemporaryFile remoteFlame = null;
        CommandExecutor.TemporaryFile remoteJfr = null;
        Map<String, Path> tempFiles = new LinkedHashMap<>();
        try {
            if (asprofAvailable) {
                try {
                    remoteFlame = executor.createTemporaryFile("jstall-flame-", ".html");
                } catch (IOException e) {
                    remoteFlame = null;
                }
            }
            if (jfrStarted) {
                try {
                    remoteJfr = executor.createTemporaryFile("jstall-jfr-", ".jfr");
                } catch (IOException e) {
                    remoteJfr = null;
                }
            }

            // Run asprof (blocks for windowMs) — or sleep so JFR has time to record.
            if (asprofAvailable && remoteFlame != null) {
                try {
                    var profResult = executor.executeCommand("asprof",
                            "-d", windowMs / 1000.0 + "s",
                            "-e", event,
                            "-i", String.valueOf(intervalNanos),
                            "-f", remoteFlame.getPath(),
                            String.valueOf(pid));
                    if (profResult.exitCode() == 0) {
                        Path localFlamePath = Files.createTempFile("jstall-flame-", ".html");
                        remoteFlame.copyInto(localFlamePath);
                        if (Files.size(localFlamePath) > 0) {
                            tempFiles.put("flame", localFlamePath);
                        } else {
                            Files.deleteIfExists(localFlamePath);
                        }
                    }
                } catch (IOException ignored) {}
            } else {
                try { Thread.sleep(windowMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }

            // Dump JFR to remote temp file and transfer to a local temp file.
            if (jfrStarted && remoteJfr != null) {
                dumpJfr(helper, recordingName, Path.of(remoteJfr.getPath()));
                try {
                    Path localJfrPath = Files.createTempFile("jstall-jfr-", ".jfr");
                    remoteJfr.copyInto(localJfrPath);
                    if (Files.size(localJfrPath) > 0) {
                        tempFiles.put("jfr", localJfrPath);
                    } else {
                        Files.deleteIfExists(localJfrPath);
                    }
                } catch (IOException ignored) {}
            }

            if (tempFiles.isEmpty()) {
                return skip(timestamp, asprofAvailable ? "profiling-failed" : "asprof-not-available");
            }

            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("windowMs", String.valueOf(windowMs));
            metadata.put("event", event);
            return new CollectedData(timestamp, "", metadata, Collections.unmodifiableMap(tempFiles));
        } catch (Exception e) {
            for (Path p : tempFiles.values()) try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            return skip(timestamp, "profiling-failed");
        } finally {
            if (jfrStarted) stopJfr(helper, recordingName);
            if (remoteFlame != null) { try { remoteFlame.delete(); } catch (IOException ignored) {} }
            if (remoteJfr   != null) { try { remoteJfr.delete();   } catch (IOException ignored) {} }
        }
    }

    private CollectedData skip(long timestamp, String reason) {
        return new CollectedData(timestamp, "", Map.of("skip", "true", "reason", reason));
    }

    private boolean startJfr(JMXDiagnosticHelper helper, String recordingName) {
        try {
            helper.executeCommand("Jfr.start", "name=" + recordingName, "settings=profile");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void dumpJfr(JMXDiagnosticHelper helper, String recordingName, Path jfrPath) {
        try {
            helper.executeCommand("JFR.dump", "name=" + recordingName, "filename=" + jfrPath);
        } catch (IOException ignored) {
        }
    }

    private void stopJfr(JMXDiagnosticHelper helper, String recordingName) {
        try {
            helper.executeCommand("JFR.stop", "name=" + recordingName);
        } catch (IOException ignored) {
        }
    }

    @Override
    public void persist(ZipOutputStream zipOut, String pidPath, List<CollectedData> samples) throws IOException {
        // Only persist the first valid sample (there should be only one)
        for (CollectedData sample : samples) {
            if (sample.metadata().containsKey("skip")) {
                continue;
            }
            boolean hasFlame = sample.tempFiles().containsKey("flame") || !sample.rawData().isBlank();
            boolean hasJfr   = sample.tempFiles().containsKey("jfr");
            if (!hasFlame && !hasJfr) {
                continue;
            }

            if (hasFlame) {
                // Write flamegraph HTML as flame.html
                ZipEntry flameZipEntry = new ZipEntry(pidPath + FLAME_SUBDIR + "flame.html");
                zipOut.putNextEntry(flameZipEntry);
                if (sample.tempFiles().containsKey("flame")) {
                    zipOut.write(Files.readAllBytes(sample.tempFiles().get("flame")));
                } else {
                    zipOut.write(sample.rawData().getBytes(StandardCharsets.UTF_8));
                }
                zipOut.closeEntry();

                // Write metadata JSON as flame.meta.json (event, windowMs, interval)
                ZipEntry metaZipEntry = new ZipEntry(pidPath + FLAME_SUBDIR + "flame.meta.json");
                zipOut.putNextEntry(metaZipEntry);
                StringBuilder metaJson = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<String, String> entry : sample.metadata().entrySet()) {
                    if (!first) metaJson.append(",");
                    metaJson.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
                    first = false;
                }
                if (!sample.metadata().isEmpty()) {
                    metaJson.append(",\"intervalNanos\":\"").append(PROFILE_SAMPLE_INTERVAL_NANOS).append("\"");
                }
                metaJson.append("}");
                zipOut.write(metaJson.toString().getBytes(StandardCharsets.UTF_8));
                zipOut.closeEntry();
            }

            if (hasJfr) {
                ZipEntry jfrZipEntry = new ZipEntry(pidPath + JFR_SUBDIR + "default.jfr");
                zipOut.putNextEntry(jfrZipEntry);
                zipOut.write(Files.readAllBytes(sample.tempFiles().get("jfr")));
                zipOut.closeEntry();
            }

            break; // only the first valid sample
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
            if (sample.metadata().containsKey("skip")) {
                continue;
            }
            boolean hasFlame = sample.tempFiles().containsKey("flame") || !sample.rawData().isBlank();
            boolean hasJfr   = sample.tempFiles().containsKey("jfr");
            if (!hasFlame && !hasJfr) {
                continue;
            }
            if (hasFlame) {
                files.add(FLAME_SUBDIR + "flame.html");
                files.add(FLAME_SUBDIR + "flame.meta.json");
            }
            if (hasJfr) {
                files.add(JFR_SUBDIR + "default.jfr");
            }
            break; // only one valid sample is persisted
        }
        return files;
    }
}