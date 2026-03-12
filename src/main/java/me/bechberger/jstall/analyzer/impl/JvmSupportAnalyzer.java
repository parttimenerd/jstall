package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.provider.requirement.DataRequirements;
import me.bechberger.jstall.util.JvmVersionChecker;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * Checks whether the JVM is reasonably up-to-date based on {@code java.version.date}
 * from {@code jcmd VM.system_properties}.
 * <p>
 * Emits no output if the JVM is &leq; 4 months old.
 * Emits a warning-like message if it's older than 4 months.
 * Returns a non-zero exit code if it's older than 1 year.
 */
public class JvmSupportAnalyzer implements Analyzer {

    static final int OUTDATED_EXIT_CODE = 10;

    private final Clock clock;

    public JvmSupportAnalyzer() {
        this(Clock.systemDefaultZone());
    }

    JvmSupportAnalyzer(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return "jvm-support";
    }

    @Override
    public Set<String> supportedOptions() {
        return Set.of();
    }

    @Override
    public DumpRequirement dumpRequirement() {
        return DumpRequirement.ANY;
    }

    @Override
    public DataRequirements getDataRequirements(Map<String, Object> options) {
        return DataRequirements.builder()
            .addThreadDump()
            .addSystemProps()
            .build();
    }

    @Override
    public AnalyzerResult analyze(ResolvedData data, Map<String, Object> options) {
        Map<String, String> props = data.systemProperties();
        if (props.isEmpty()) {
            // If we can't determine the release date, don't spam status output.
            return AnalyzerResult.nothing();
        }

        String dateStr = props.get("java.version.date");
        LocalDate releaseDate = JvmVersionChecker.parseVersionDate(dateStr);
        if (releaseDate == null) {
            return AnalyzerResult.nothing();
        }

        LocalDate today = LocalDate.now(clock);
        if (today.isBefore(releaseDate)) {
            // Clock skew or weird value; ignore.
            return AnalyzerResult.nothing();
        }

        if (JvmVersionChecker.isCurrentRelease(dateStr, clock)) {
            return AnalyzerResult.nothing();
        }

        String vendor = props.getOrDefault("java.vendor", "<unknown vendor>");
        String version = props.getOrDefault("java.version", "<unknown version>");
        String vmVersion = props.getOrDefault("java.vendor.version", "");

        String agePretty = JvmVersionChecker.prettyAge(releaseDate, today);

        String message = "JVM looks outdated based on java.version.date=" + releaseDate + " (" + agePretty + ")\n" +
                         "Detected: java.vendor=" + vendor + ", java.version=" + version +
                         (vmVersion.isBlank() ? "" : ", java.vendor.version=" + vmVersion) + "\n" +
                         "Recommendation: update the JVM (supported JVMs are typically released within the last ~4 months).";

        if (JvmVersionChecker.isOutdated(dateStr, clock)) {
            return AnalyzerResult.withExitCode(message, OUTDATED_EXIT_CODE);
        }

        return AnalyzerResult.ok(message);
    }
}