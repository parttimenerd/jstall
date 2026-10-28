package me.bechberger.jstall.util;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Shared utility for checking JVM version freshness based on {@code java.version.date}.
 *
 * <p>Used by {@code JvmSupportAnalyzer}, {@code AsyncProfilerWindowRequirement},
 * and {@code ReadmeWriter} to avoid duplicating the threshold logic.</p>
 */
public final class JvmVersionChecker {

    /** JVMs released within the last 4 months are considered current. */
    public static final int CURRENT_MONTHS = 4;
    /** JVMs older than 1 year are considered seriously outdated. */
    public static final int OUTDATED_YEARS = 1;

    private JvmVersionChecker() {
    }

    /**
     * Safely parse a {@code java.version.date} value (expected format: {@code yyyy-MM-dd}).
     *
     * @return the parsed date, or {@code null} if the input is null, blank, or unparseable
     */
    public static LocalDate parseVersionDate(String versionDateStr) {
        if (versionDateStr == null || versionDateStr.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(versionDateStr.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Returns {@code true} if the JVM release date is within the last {@value #CURRENT_MONTHS} months.
     *
     * @param versionDateStr value of {@code java.version.date} (e.g. "2025-07-15")
     * @param clock          clock to use for "today"
     */
    public static boolean isCurrentRelease(String versionDateStr, Clock clock) {
        LocalDate releaseDate = parseVersionDate(versionDateStr);
        if (releaseDate == null) {
            return false;
        }
        LocalDate threshold = LocalDate.now(clock).minusMonths(CURRENT_MONTHS);
        return !releaseDate.isBefore(threshold);
    }

    /**
     * Returns {@code true} if the JVM release date is older than {@value #OUTDATED_YEARS} year(s).
     *
     * @param versionDateStr value of {@code java.version.date} (e.g. "2024-01-01")
     * @param clock          clock to use for "today"
     */
    public static boolean isOutdated(String versionDateStr, Clock clock) {
        LocalDate releaseDate = parseVersionDate(versionDateStr);
        if (releaseDate == null) {
            return false;
        }
        LocalDate threshold = LocalDate.now(clock).minusYears(OUTDATED_YEARS);
        return releaseDate.isBefore(threshold);
    }

    /**
     * Produce a stable, human-readable age string like "4mo 24d old" or "1y 2mo old".
     *
     * @param fromInclusive release date (inclusive)
     * @param toExclusive   today's date (exclusive)
     */
    public static String prettyAge(LocalDate fromInclusive, LocalDate toExclusive) {
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
