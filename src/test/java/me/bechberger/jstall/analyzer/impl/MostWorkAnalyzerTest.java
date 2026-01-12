package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MostWorkAnalyzerTest {

    @Test
    void testName() {
        MostWorkAnalyzer analyzer = new MostWorkAnalyzer();
        assertEquals("most-work", analyzer.name());
    }

    @Test
    void testSupportedOptions() {
        MostWorkAnalyzer analyzer = new MostWorkAnalyzer();
        Set<String> supported = analyzer.supportedOptions();

        assertTrue(supported.contains("dumps"));
        assertTrue(supported.contains("interval"));
        assertTrue(supported.contains("keep"));
        assertTrue(supported.contains("top"));
        assertTrue(supported.contains("no-native"));
        assertTrue(supported.contains("stack-depth"));
        assertEquals(6, supported.size());
    }

    @Test
    void testDumpRequirement() {
        MostWorkAnalyzer analyzer = new MostWorkAnalyzer();
        assertEquals(DumpRequirement.MANY, analyzer.dumpRequirement());
    }

    @Test
    void testAnalyzeWithNoDumps() {
        MostWorkAnalyzer analyzer = new MostWorkAnalyzer();

        // MANY requirement means empty list should work (but would fail in runner validation)
        AnalyzerResult result = analyzer.analyze(List.of(), Map.of());

        assertEquals(0, result.exitCode());
        assertNotNull(result.output());
    }

    @Test
    void testTopOption() {
        MostWorkAnalyzer analyzer = new MostWorkAnalyzer();

        // With no dumps, should still respect the top option
        AnalyzerResult result = analyzer.analyze(List.of(), Map.of("top", 5));

        assertEquals(0, result.exitCode());
        assertNotNull(result.output());
    }
}