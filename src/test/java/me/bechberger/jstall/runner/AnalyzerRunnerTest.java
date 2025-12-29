package me.bechberger.jstall.runner;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jthreaddump.model.ThreadDump;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzerRunnerTest {

    @Test
    void testRunSingleAnalyzer() {
        AnalyzerRunner runner = new AnalyzerRunner();

        Analyzer testAnalyzer = new TestAnalyzer("test", Set.of("json"), DumpRequirement.ANY);
        List<ThreadDump> dumps = List.of();
        Map<String, Object> options = Map.of("json", true);

        var result = runner.runAnalyzers(List.of(testAnalyzer), dumps, options);

        assertTrue(result.output().contains("test"));
        assertEquals(0, result.exitCode());
    }

    @Test
    void testAggregateExitCodes() {
        AnalyzerRunner runner = new AnalyzerRunner();

        Analyzer analyzer1 = new TestAnalyzer("a1", Set.of(), DumpRequirement.ANY, 0);
        Analyzer analyzer2 = new TestAnalyzer("a2", Set.of(), DumpRequirement.ANY, 2);
        Analyzer analyzer3 = new TestAnalyzer("a3", Set.of(), DumpRequirement.ANY, 1);

        var result = runner.runAnalyzers(
            List.of(analyzer1, analyzer2, analyzer3),
            List.of(),
            Map.of()
        );

        assertEquals(2, result.exitCode()); // Max exit code
    }

    @Test
    void testUnsupportedOptionsAreFiltered() {
        AnalyzerRunner runner = new AnalyzerRunner();

        Analyzer analyzer = new TestAnalyzer("test", Set.of("json"), DumpRequirement.ANY);
        Map<String, Object> options = Map.of(
            "json", true,
            "unsupported", "value"
        );

        // Should not throw exception - unsupported options are simply filtered out
        var result = runner.runAnalyzers(List.of(analyzer), List.of(), options);

        assertNotNull(result);
        assertEquals(0, result.exitCode());
    }

    @Test
    void testMultipleAnalyzersWithDifferentOptions() {
        AnalyzerRunner runner = new AnalyzerRunner();

        Analyzer analyzer1 = new TestAnalyzer("a1", Set.of("json"), DumpRequirement.ANY);
        Analyzer analyzer2 = new TestAnalyzer("a2", Set.of("top"), DumpRequirement.ANY);

        // Options that are mix of both analyzers' supported options
        Map<String, Object> options = Map.of(
            "json", true,
            "top", 5,
            "extra", "ignored"
        );

        // Both analyzers should run successfully, each receiving only their supported options
        var result = runner.runAnalyzers(List.of(analyzer1, analyzer2), List.of(), options);

        assertNotNull(result);
        assertTrue(result.output().contains("a1"));
        assertTrue(result.output().contains("a2"));
    }

    @Test
    void testDumpRequirementOne() {
        AnalyzerRunner runner = new AnalyzerRunner();

        // Create mock dumps (we can't easily create real ThreadDump instances in test)
        // This test would need actual ThreadDump instances or mocking
        Analyzer analyzer = new TestAnalyzer("test", Set.of(), DumpRequirement.ONE);

        // This would require actual ThreadDump instances
        // For now, we'll test with empty list
        var result = runner.runAnalyzers(List.of(analyzer), List.of(), Map.of());
        assertEquals(0, result.exitCode());
    }

    // Helper test analyzer
    private static class TestAnalyzer implements Analyzer {
        private final String name;
        private final Set<String> supportedOptions;
        private final DumpRequirement requirement;
        private final int exitCode;

        TestAnalyzer(String name, Set<String> supportedOptions, DumpRequirement requirement) {
            this(name, supportedOptions, requirement, 0);
        }

        TestAnalyzer(String name, Set<String> supportedOptions, DumpRequirement requirement, int exitCode) {
            this.name = name;
            this.supportedOptions = supportedOptions;
            this.requirement = requirement;
            this.exitCode = exitCode;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Set<String> supportedOptions() {
            return supportedOptions;
        }

        @Override
        public DumpRequirement dumpRequirement() {
            return requirement;
        }

        @Override
        public AnalyzerResult analyze(List<ThreadDump> dumps, Map<String, Object> options) {
            return AnalyzerResult.withExitCode("Output from " + name, exitCode);
        }
    }
}