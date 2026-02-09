package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
import me.bechberger.jthreaddump.parser.ThreadDumpParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class JvmSupportAnalyzerTest {

    private static ThreadDumpSnapshot snapshotWithReleaseDate(LocalDate releaseDate) {
        String dumpContent = """
            2024-12-29 13:00:00
            Full thread dump Java HotSpot(TM) 64-Bit Server VM (21+35-2513 mixed mode):
            """;
        try {
            var parsed = ThreadDumpParser.parse(dumpContent);
            return new ThreadDumpSnapshot(parsed, dumpContent, null,
                Map.of(
                    "java.version.date", releaseDate.toString(),
                    "java.vendor", "TestVendor",
                    "java.version", "21.0.1"
                ));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void emitsNothingIfYoungEnough() {
        LocalDate today = LocalDate.of(2026, 2, 9);
        Clock clock = Clock.fixed(Instant.parse("2026-02-09T10:00:00Z"), ZoneOffset.UTC);
        JvmSupportAnalyzer analyzer = new JvmSupportAnalyzer(clock);

        var snapshot = snapshotWithReleaseDate(today.minusMonths(3));
        AnalyzerResult result = analyzer.analyze(List.of(snapshot), Map.of());

        assertFalse(result.shouldDisplay());
        assertEquals(0, result.exitCode());
    }

    @Test
    void warnsWhenOlderThanFourMonths() {
        Clock clock = Clock.fixed(Instant.parse("2026-02-09T10:00:00Z"), ZoneOffset.UTC);
        JvmSupportAnalyzer analyzer = new JvmSupportAnalyzer(clock);

        var snapshot = snapshotWithReleaseDate(LocalDate.of(2025, 8, 1));
        AnalyzerResult result = analyzer.analyze(List.of(snapshot), Map.of());

        assertTrue(result.shouldDisplay());
        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("outdated"));
    }

    @Test
    void errorsWhenOlderThanAYear() {
        Clock clock = Clock.fixed(Instant.parse("2026-02-09T10:00:00Z"), ZoneOffset.UTC);
        JvmSupportAnalyzer analyzer = new JvmSupportAnalyzer(clock);

        var snapshot = snapshotWithReleaseDate(LocalDate.of(2024, 1, 1));
        AnalyzerResult result = analyzer.analyze(List.of(snapshot), Map.of());

        assertTrue(result.shouldDisplay());
        assertEquals(JvmSupportAnalyzer.OUTDATED_EXIT_CODE, result.exitCode());
        assertTrue(result.output().contains("Recommendation"));
    }
}