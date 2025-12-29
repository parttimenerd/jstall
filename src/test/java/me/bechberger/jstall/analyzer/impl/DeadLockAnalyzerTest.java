package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DeadLockAnalyzerTest {

    @Test
    void testName() {
        DeadLockAnalyzer analyzer = new DeadLockAnalyzer();
        assertEquals("dead-lock", analyzer.name());
    }

    @Test
    void testSupportedOptions() {
        DeadLockAnalyzer analyzer = new DeadLockAnalyzer();
        Set<String> supported = analyzer.supportedOptions();

        assertTrue(supported.contains("keep"));
        assertTrue(supported.contains("json"));
        assertEquals(2, supported.size());
    }

    @Test
    void testDumpRequirement() {
        DeadLockAnalyzer analyzer = new DeadLockAnalyzer();
        assertEquals(DumpRequirement.ONE, analyzer.dumpRequirement());
    }

    @Test
    void testAnalyzeWithNoDumps() {
        DeadLockAnalyzer analyzer = new DeadLockAnalyzer();
        AnalyzerResult result = analyzer.analyze(List.of(), Map.of());

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("No thread dump"));
    }

    @Test
    void testJsonOutput() {
        DeadLockAnalyzer analyzer = new DeadLockAnalyzer();
        AnalyzerResult result = analyzer.analyze(List.of(), Map.of("json", true));

        assertEquals(0, result.exitCode());
        // With no dumps, should still return valid response
        assertNotNull(result.output());
    }
}