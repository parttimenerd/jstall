package me.bechberger.jstall.provider;

import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jstall.provider.requirement.DataRequirement;
import me.bechberger.jstall.provider.requirement.DataRequirements;
import me.bechberger.jstall.provider.requirement.JcmdRequirement;
import me.bechberger.jstall.util.JcmdOutputParsers;
import me.bechberger.jstall.util.JvmVersionChecker;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates a user-friendly README.md for inclusion in a jstall recording ZIP archive.
 *
 * <p>The README includes a table of contents, a warning banner for outdated JVMs,
 * per-JVM metadata (version, vendor, OS), and a listing of every file in the archive.
 * Adding a new {@link DataRequirement} automatically contributes to the README via
 * {@link DataRequirement#getDirectoryDescription()} and
 * {@link DataRequirement#getExpectedFiles(List)}.</p>
 */
public class ReadmeWriter {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final List<RecordingProvider.CollectedJvmData> collected;
    private final DataRequirements requirements;
    private final String jstallVersion;
    private final Clock clock;

    /** Per-JVM metadata extracted from collected system properties. */
    private final List<JvmInfo> jvmInfos;

    ReadmeWriter(List<RecordingProvider.CollectedJvmData> collected,
                 DataRequirements requirements,
                 String jstallVersion,
                 Clock clock) {
        this.collected = collected;
        this.requirements = requirements;
        this.jstallVersion = jstallVersion;
        this.clock = clock;
        this.jvmInfos = collected.stream()
                .map(jvm -> extractJvmInfo(jvm, requirements))
                .toList();
    }

    ReadmeWriter(List<RecordingProvider.CollectedJvmData> collected,
                 DataRequirements requirements,
                 String jstallVersion) {
        this(collected, requirements, jstallVersion, Clock.systemUTC());
    }

    /**
     * Generates the full README.md content.
     */
    public String generate() {
        StringBuilder sb = new StringBuilder();
        writeHeader(sb);
        writeWarnings(sb);
        writeTableOfContents(sb);
        writeConfiguration(sb);
        for (int i = 0; i < collected.size(); i++) {
            writeJvmSection(sb, collected.get(i), jvmInfos.get(i));
        }
        writeUsage(sb);
        return sb.toString();
    }

    // ---- Section writers -----------------------------------------------------------------------

    private void writeHeader(StringBuilder sb) {
        sb.append("# JStall Recording Archive\n\n");
        sb.append("Created by `jstall record`");
        if (jstallVersion != null && !jstallVersion.isBlank()) {
            sb.append(" v").append(jstallVersion);
        }
        sb.append(" on **").append(TIMESTAMP_FMT.format(Instant.now(clock))).append(" UTC**.\n\n");
        sb.append("Project: <https://github.com/parttimenerd/jstall>\n\n");
    }

    private void writeWarnings(StringBuilder sb) {
        List<String> warnings = new ArrayList<>();

        for (int i = 0; i < collected.size(); i++) {
            JvmInfo info = jvmInfos.get(i);
            long pid = collected.get(i).process().pid();

            if (info.versionDate == null) {
                continue;
            }
            String dateStr = info.versionDate;
            LocalDate releaseDate = JvmVersionChecker.parseVersionDate(dateStr);
            if (releaseDate == null) {
                continue;
            }

            if (JvmVersionChecker.isOutdated(dateStr, clock)) {
                String age = JvmVersionChecker.prettyAge(releaseDate, LocalDate.now(clock));
                warnings.add(String.format(
                        "JVM **%d** (`java.version=%s`, released %s, %s) is **significantly outdated** (>1 year). " +
                        "Consider upgrading to a supported JVM release.",
                        pid, info.javaVersion, dateStr, age));
            } else if (!JvmVersionChecker.isCurrentRelease(dateStr, clock)) {
                String age = JvmVersionChecker.prettyAge(releaseDate, LocalDate.now(clock));
                warnings.add(String.format(
                        "JVM **%d** (`java.version=%s`, released %s, %s) is older than 4 months.",
                        pid, info.javaVersion, dateStr, age));
            }
        }

        if (!warnings.isEmpty()) {
            sb.append("> **⚠️ Warning: Outdated JVM detected**\n");
            sb.append(">\n");
            for (String warning : warnings) {
                sb.append("> ").append(warning).append("\n>\n");
            }
            sb.append("\n");
        }
    }

    private void writeTableOfContents(StringBuilder sb) {
        sb.append("## Table of Contents\n\n");
        sb.append("- [Recording Configuration](#recording-configuration)\n");
        for (RecordingProvider.CollectedJvmData jvm : collected) {
            long pid = jvm.process().pid();
            String shortClass = shortMainClass(jvm.process().mainClass());
            sb.append("- [JVM ").append(pid).append(" — ").append(shortClass).append("](#jvm-").append(pid).append(")\n");
        }
        sb.append("- [Usage](#usage)\n");
        sb.append("\n");
    }

    private void writeConfiguration(StringBuilder sb) {
        sb.append("## Recording Configuration\n\n");

        int maxCount = requirements.getRequirements().stream()
                .mapToInt(r -> r.getSchedule().count())
                .max()
                .orElse(1);
        long intervalMs = requirements.getRequirements().stream()
                .filter(r -> r.getSchedule().intervalMs() > 0)
                .mapToLong(r -> r.getSchedule().intervalMs())
                .findFirst()
                .orElse(0);

        sb.append("| Setting | Value |\n");
        sb.append("|---------|-------|\n");
        sb.append("| Sample count | ").append(maxCount).append(" |\n");
        if (intervalMs > 0) {
            sb.append("| Sample interval | ").append(intervalMs).append(" ms |\n");
        }
        sb.append("| Total JVMs | ").append(collected.size()).append(" |\n");

        // List collected data types
        List<String> types = new ArrayList<>();
        for (DataRequirement req : requirements.getRequirements()) {
            String desc = req.getDirectoryDescription();
            if (desc != null) {
                types.add(req.getType() + " — " + desc);
            } else {
                types.add(req.getType());
            }
        }
        if (!types.isEmpty()) {
            sb.append("| Data types | ").append(String.join(", ", types)).append(" |\n");
        }
        sb.append("\n");
    }

    private void writeJvmSection(StringBuilder sb,
                                 RecordingProvider.CollectedJvmData jvm,
                                 JvmInfo info) {
        long pid = jvm.process().pid();
        String shortClass = shortMainClass(jvm.process().mainClass());

        // Heading with anchor
        sb.append("<a id=\"jvm-").append(pid).append("\"></a>\n\n");
        sb.append("## JVM ").append(pid).append(" — ").append(shortClass);
        if (!jvm.successful()) {
            sb.append(" [FAILED]");
        }
        sb.append("\n\n");

        // Info table
        sb.append("| Property | Value |\n");
        sb.append("|----------|-------|\n");
        sb.append("| PID | ").append(pid).append(" |\n");
        sb.append("| Main class | `").append(jvm.process().mainClass()).append("` |\n");
        if (info.javaVersion != null) {
            String ver = info.javaVersion;
            if (info.vendorVersion != null && !info.vendorVersion.isBlank()) {
                ver += " (" + info.vendorVersion + ")";
            }
            sb.append("| Java version | ").append(ver).append(" |\n");
        }
        if (info.vendor != null) {
            sb.append("| Vendor | ").append(info.vendor).append(" |\n");
        }
        if (info.versionDate != null) {
            sb.append("| Release date | ").append(info.versionDate).append(" |\n");
        }
        if (info.runtimeName != null) {
            sb.append("| Runtime | ").append(info.runtimeName).append(" |\n");
        }
        if (info.osName != null || info.osArch != null) {
            String os = (info.osName != null ? info.osName : "");
            if (info.osArch != null) {
                os += (os.isEmpty() ? "" : " ") + info.osArch;
            }
            sb.append("| OS | ").append(os).append(" |\n");
        }
        if (info.vmUptime != null && !info.vmUptime.isBlank()) {
            sb.append("| VM uptime | ").append(escapeMarkdownTable(info.vmUptime)).append(" |\n");
        }
        sb.append("| Status | ").append(jvm.successful() ? "✅ Success" : "❌ Failed").append(" |\n");
        sb.append("| Recorded | ")
                .append(formatTimestamp(jvm.startedAt()))
                .append(" → ")
                .append(formatTimestamp(jvm.finishedAt()))
                .append(" UTC |\n");
        if (!jvm.successful() && jvm.errorMessage() != null) {
            sb.append("| Error | ").append(escapeMarkdownTable(jvm.errorMessage())).append(" |\n");
        }
        sb.append("\n");

        // File listing
        if (jvm.successful()) {
            writeFileListing(sb, jvm, pid);
        }
    }

    private void writeFileListing(StringBuilder sb,
                                  RecordingProvider.CollectedJvmData jvm,
                                  long pid) {
        sb.append("**Files:**\n\n");

        // Always present: manifest.json
        sb.append("- [").append(pid).append("/manifest.json](./").append(pid).append("/manifest.json)\n");

        for (Map.Entry<DataRequirement, List<CollectedData>> entry : jvm.data().entrySet()) {
            List<CollectedData> samples = entry.getValue();
            if (samples.isEmpty()) {
                continue;
            }
            List<String> files = entry.getKey().getExpectedFiles(samples);
            for (String file : files) {
                sb.append("- [").append(pid).append("/").append(file)
                  .append("](./").append(pid).append("/").append(file).append(")");

                // Annotate timestamped files with human-readable dates
                String humanTs = extractTimestampAnnotation(file);
                if (humanTs != null) {
                    sb.append(" — ").append(humanTs);
                }
                sb.append("\n");
            }
        }
        sb.append("\n");
    }

    private void writeUsage(StringBuilder sb) {
        sb.append("## Usage\n\n");
        sb.append("To replay and analyze this recording, use:\n\n");
        sb.append("```bash\n");
        sb.append("jstall -f <recording.zip> status\n");
        sb.append("jstall -f <recording.zip> threads\n");
        sb.append("# ... or other analyzers that support the collected data types.\n");
        sb.append("```\n\n");
        sb.append("You can also extract the ZIP and examine individual files manually.\n");
        sb.append("Flamegraph HTML files can be opened directly in a web browser.\n");
    }

    // ---- JVM info extraction -------------------------------------------------------------------

    /**
     * Metadata extracted from collected system properties for a single JVM.
     */
    record JvmInfo(
            String javaVersion,
            String vendorVersion,
            String vendor,
            String versionDate,
            String runtimeName,
            String osName,
            String osArch,
            String vmUptime
    ) {
        static final JvmInfo EMPTY = new JvmInfo(null, null, null, null, null, null, null, null);
    }

    static JvmInfo extractJvmInfo(RecordingProvider.CollectedJvmData jvm,
                                  DataRequirements requirements) {
        if (!jvm.successful()) {
            return JvmInfo.EMPTY;
        }

        String vmUptime = null;
        for (Map.Entry<DataRequirement, List<CollectedData>> entry : jvm.data().entrySet()) {
            DataRequirement req = entry.getKey();
            if (req instanceof JcmdRequirement jcmd && "VM.uptime".equals(jcmd.getCommand())) {
                List<CollectedData> samples = entry.getValue();
                if (!samples.isEmpty()) {
                    vmUptime = normalizeVmUptime(samples.get(0).rawData());
                }
                break;
            }
        }

        // Search the collected data for system-properties (a JcmdRequirement with command VM.system_properties).
        // We iterate the data map directly rather than using requirements as keys, because the
        // requirement instances used during collection may differ from the builder instances.
        for (Map.Entry<DataRequirement, List<CollectedData>> entry : jvm.data().entrySet()) {
            DataRequirement req = entry.getKey();
            if (req instanceof JcmdRequirement jcmd && "VM.system_properties".equals(jcmd.getCommand())) {
                List<CollectedData> samples = entry.getValue();
                if (!samples.isEmpty()) {
                    Map<String, String> props = JcmdOutputParsers.parseVmSystemProperties(samples.get(0).rawData());
                    return new JvmInfo(
                            props.get("java.version"),
                            props.get("java.vendor.version"),
                            props.get("java.vendor"),
                            props.get("java.version.date"),
                            props.get("java.runtime.name"),
                            props.get("os.name"),
                            props.get("os.arch"),
                            vmUptime
                    );
                }
            }
        }
        return JvmInfo.EMPTY;
    }

    private static String normalizeVmUptime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<String> lines = raw.lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .toList();
        if (lines.isEmpty()) {
            return null;
        }
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (!line.matches("\\d+:")) {
                return line;
            }
        }
        return lines.get(lines.size() - 1);
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private String formatTimestamp(long epochMs) {
        return TIMESTAMP_FMT.format(Instant.ofEpochMilli(epochMs));
    }

    /**
     * Try to extract a human-readable timestamp annotation from a filename like "000-1772815039175.txt".
     */
    private String extractTimestampAnnotation(String filename) {
        // Match pattern: digits dash digits dot extension (e.g. "000-1772815039175.txt")
        String baseName = filename;
        int lastSlash = baseName.lastIndexOf('/');
        if (lastSlash >= 0) {
            baseName = baseName.substring(lastSlash + 1);
        }
        int dashIdx = baseName.indexOf('-');
        int dotIdx = baseName.lastIndexOf('.');
        if (dashIdx > 0 && dotIdx > dashIdx) {
            try {
                long ts = Long.parseLong(baseName.substring(dashIdx + 1, dotIdx));
                if (ts > 1_000_000_000_000L) { // looks like epoch millis
                    return TIMESTAMP_FMT.format(Instant.ofEpochMilli(ts)) + " UTC";
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /**
     * Shorten a main class path for display (e.g. keep last path component or just the class name).
     */
    static String shortMainClass(String mainClass) {
        if (mainClass == null || mainClass.isBlank()) {
            return "<unknown>";
        }
        // For class names like com.example.Main, keep the simple name
        int lastDot = mainClass.lastIndexOf('.');
        int lastSlash = mainClass.lastIndexOf('/');
        if (lastSlash >= 0) {
            // Path-style: /Applications/.../something — use last component
            String last = mainClass.substring(lastSlash + 1);
            return last.isEmpty() ? mainClass : last;
        }
        if (lastDot >= 0) {
            return mainClass.substring(lastDot + 1);
        }
        return mainClass;
    }

    private static String escapeMarkdownTable(String text) {
        if (text == null) return "";
        return text.replace("|", "\\|").replace("\n", " ");
    }
}