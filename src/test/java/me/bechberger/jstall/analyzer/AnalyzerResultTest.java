package me.bechberger.jstall.analyzer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzerResultTest {

    @Test
    void testOkWithoutOutput() {
        AnalyzerResult result = AnalyzerResult.ok();
        assertEquals("", result.output());
        assertEquals(0, result.exitCode());
    }

    @Test
    void testOkWithOutput() {
        AnalyzerResult result = AnalyzerResult.ok("All good");
        assertEquals("All good", result.output());
        assertEquals(0, result.exitCode());
    }

    @Test
    void testDeadlock() {
        AnalyzerResult result = AnalyzerResult.deadlock("Deadlock detected");
        assertEquals("Deadlock detected", result.output());
        assertEquals(2, result.exitCode());
    }

    @Test
    void testCustomExitCode() {
        AnalyzerResult result = AnalyzerResult.withExitCode("Warning", 1);
        assertEquals("Warning", result.output());
        assertEquals(1, result.exitCode());
    }
}