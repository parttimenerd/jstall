package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.AnalyzerOutput;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.impl.StatusAnalyzer;
import me.bechberger.jstall.analyzer.ResolvedData;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveModeRunnerTest {

    @Test
    void statusAnalyzer_firstSample_showsTabsWithPlaceholders() {
        StatusAnalyzer status = new StatusAnalyzer();
        // Analyze with empty data (no dumps) — simulates first sample
        ResolvedData emptyData = ResolvedData.fromDumps(java.util.List.of());
        AnalyzerResult result = status.analyze(emptyData, Map.of());

        assertInstanceOf(AnalyzerOutput.CompositeOutput.class, result.structured());
        AnalyzerOutput.CompositeOutput composite = (AnalyzerOutput.CompositeOutput) result.structured();

        // Should have sections for MANY analyzers showing "Collecting data..."
        assertTrue(composite.sections().stream()
            .anyMatch(s -> s.content().render().contains("Collecting data...")));
    }

    @Test
    void statusAnalyzer_firstSample_singleDumpAnalyzersRun() {
        StatusAnalyzer status = new StatusAnalyzer();
        ResolvedData emptyData = ResolvedData.fromDumps(java.util.List.of());
        AnalyzerResult result = status.analyze(emptyData, Map.of());

        assertInstanceOf(AnalyzerOutput.CompositeOutput.class, result.structured());
        AnalyzerOutput.CompositeOutput composite = (AnalyzerOutput.CompositeOutput) result.structured();

        // Should have tabs for the MANY analyzers (most-work, threads)
        assertTrue(composite.sections().stream()
            .anyMatch(s -> "most-work".equals(s.name())));
        assertTrue(composite.sections().stream()
            .anyMatch(s -> "threads".equals(s.name())));
    }
}
