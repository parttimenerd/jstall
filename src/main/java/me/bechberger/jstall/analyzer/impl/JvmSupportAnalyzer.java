package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.model.ThreadDumpSnapshot;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks whether the JVM is reasonably up-to-date based on {@code java.version.date}
 * from {@code jcmd VM.system_properties}.
 *
 * Emits no output if the JVM is <= 4 months old.
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
    public AnalyzerResult analyze(List<ThreadDumpSnapshot> dumpsWithRaw, Map<String, Object> options) {
        if (dumpsWithRaw == null || dumpsWithRaw.isEmpty()) {
            return AnalyzerResult.nothing();
        }

        Map<String, String> props = dumpsWithRaw.get(0).systemProperties();
        if (props == null || props.isEmpty()) {
            // If we can't determine the release date, don't spam status output.
            return AnalyzerResult.nothing();
        }

        String dateStr = props.get("java.version.date");
        if (dateStr == null || dateStr.isBlank()) {
            return AnalyzerResult.nothing();
        }

        LocalDate releaseDate;
        try {
            // Expected format: yyyy-MM-dd (as seen in jcmd output)
            releaseDate = LocalDate.parse(dateStr.trim());
        } catch (DateTimeParseException e) {
            return AnalyzerResult.nothing();
        }

        LocalDate today = LocalDate.now(clock);
        long ageDays = ChronoUnit.DAYS.between(releaseDate, today);
        if (ageDays < 0) {
            // Clock skew or weird value; ignore.
            return AnalyzerResult.nothing();
        }

        LocalDate fourMonthsAgo = today.minusMonths(4);
        LocalDate oneYearAgo = today.minusYears(1);

        if (!releaseDate.isBefore(fourMonthsAgo)) {
            return AnalyzerResult.nothing();
        }

        String vendor = props.getOrDefault("java.vendor", "<unknown vendor>");
        String version = props.getOrDefault("java.version", "<unknown version>");
        String vmVersion = props.getOrDefault("java.vendor.version", "");

        String agePretty = prettyAge(releaseDate, today);

        String message = "JVM looks outdated based on java.version.date=" + releaseDate + " (" + agePretty + ")\n" +
                         "Detected: java.vendor=" + vendor + ", java.version=" + version +
                         (vmVersion.isBlank() ? "" : ", java.vendor.version=" + vmVersion) + "\n" +
                         "Recommendation: update the JVM (supported JVMs are typically released within the last ~4 months).";

        if (releaseDate.isBefore(oneYearAgo)) {
            return AnalyzerResult.withExitCode(message, OUTDATED_EXIT_CODE);
        }

        return AnalyzerResult.ok(message);
    }

    /**
     * Produce a stable, non-misleading age string like "4mo 24d" or "1y 2mo".
     *
     * We intentionally don't use Period.toString() because it can be confusing (and can normalize in surprising ways).
     */
    private static String prettyAge(LocalDate fromInclusive, LocalDate toExclusive) {
        if (toExclusive.isBefore(fromInclusive)) {
            return "0d old";
        }
        LocalDate cursor = fromInclusive;

        long years = cursor.until(toExclusive, ChronoUnit.YEARS);
        cursor = cursor.plusYears(years);

        long months = cursor.until(toExclusive, ChronoUnit.MONTHS);
        cursor = cursor.plusMonths(months);

        long days = cursor.until(toExclusive, ChronoUnit.DAYS);

        StringBuilder sb = new StringBuilder();
        if (years > 0) {
            sb.append(years).append("y");
        }
        if (months > 0) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(months).append("mo");
        }
        if (days > 0 || sb.isEmpty()) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(days).append("d");
        }
        sb.append(" old");
        return sb.toString();
    }
}