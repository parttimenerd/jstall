package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.minicli.RunResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BaseAnalyzerCommand multi-execution functionality.
 */
class BaseAnalyzerCommandMultiExecutionTest {

    // Simple test analyzer
        private record TestAnalyzer(String name, boolean supportsMultiple) implements Analyzer {

        @Override
            public Set<String> supportedOptions() {
                return Set.of("dumps", "interval", "keep");
            }

            @Override
            public DumpRequirement dumpRequirement() {
                return DumpRequirement.ANY;
            }

            @Override
            public AnalyzerResult analyzeThreadDumps(List<ThreadDump> dumps,
                                                     Map<String, Object> options) {
                return AnalyzerResult.ok("Analysis result for " + name);
            }
        }

    // Test command implementation
    private static class TestAnalyzerCommand extends BaseAnalyzerCommand {
        private final Analyzer analyzer;
        private final boolean supportsMultiple;

        TestAnalyzerCommand(Analyzer analyzer, boolean supportsMultiple) {
            this.analyzer = analyzer;
            this.supportsMultiple = supportsMultiple;
        }

        @Override
        protected Analyzer getAnalyzer() {
            return analyzer;
        }

        @Override
        protected boolean supportsMultipleTargets() {
            return supportsMultiple;
        }
    }

    @Test
    void testMultipleFileTargets() throws Exception {
        // Create test thread dump files
        Path file1 = Files.createTempFile("dump1", ".txt");
        Path file2 = Files.createTempFile("dump2", ".txt");

        try {
            String dumpContent = """
                2024-12-29 13:00:00
                Full thread dump Java HotSpot(TM) 64-Bit Server VM:
                
                "main" #1 prio=5 runnable
                """;

            Files.writeString(file1, dumpContent);
            Files.writeString(file2, dumpContent);

            TestAnalyzer analyzer = new TestAnalyzer("test", true);
            TestAnalyzerCommand cmd = new TestAnalyzerCommand(analyzer, true);
            cmd.targets = List.of(file1.toString(), file2.toString());

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream originalOut = System.out;
            System.setOut(new PrintStream(out));
            try {
                int exitCode = cmd.call();
                assertEquals(0, exitCode);
            } finally {
                System.setOut(originalOut);
            }

            String output = out.toString();
            // Should contain analysis for both files
            assertTrue(output.contains(file1.toString()) || output.contains("file:"));
            assertTrue(output.contains(file2.toString()) || output.contains("file:"));

            // Should contain separator between results
            assertTrue(output.contains("=".repeat(80)) || output.split("Analysis for").length > 1);

        } finally {
            Files.deleteIfExists(file1);
            Files.deleteIfExists(file2);
        }
    }

    @Test
    void testMultipleTargetsNotSupported() throws Exception {
        Path file1 = Files.createTempFile("dump1", ".txt");
        Path file2 = Files.createTempFile("dump2", ".txt");

        try {
            String dumpContent = """
                2024-12-29 13:00:00
                Full thread dump Java HotSpot(TM) 64-Bit Server VM:
                
                "main" #1 prio=5 runnable
                """;

            Files.writeString(file1, dumpContent);
            Files.writeString(file2, dumpContent);

            TestAnalyzer analyzer = new TestAnalyzer("test", false);
            TestAnalyzerCommand cmd = new TestAnalyzerCommand(analyzer, false); // Does NOT support multiple
            cmd.targets = List.of(file1.toString(), file2.toString());

            ByteArrayOutputStream err = new ByteArrayOutputStream();
            PrintStream originalErr = System.err;
            System.setErr(new PrintStream(err));
            try {
                int exitCode = cmd.call();
                assertEquals(1, exitCode); // Should fail
            } finally {
                System.setErr(originalErr);
            }

            String errorOutput = err.toString();
            assertTrue(errorOutput.contains("does not support multiple targets"));

        } finally {
            Files.deleteIfExists(file1);
            Files.deleteIfExists(file2);
        }
    }

    @Test
    void testSingleTargetExecution() throws Exception {
        Path file = Files.createTempFile("dump", ".txt");

        try {
            String dumpContent = """
                2024-12-29 13:00:00
                Full thread dump Java HotSpot(TM) 64-Bit Server VM:
                
                "main" #1 prio=5 runnable
                """;

            Files.writeString(file, dumpContent);

            TestAnalyzer analyzer = new TestAnalyzer("test", true);
            TestAnalyzerCommand cmd = new TestAnalyzerCommand(analyzer, true);
            cmd.targets = List.of(file.toString());

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream originalOut = System.out;
            System.setOut(new PrintStream(out));
            try {
                int exitCode = cmd.call();
                assertEquals(0, exitCode);
            } finally {
                System.setOut(originalOut);
            }

            String output = out.toString();
            assertTrue(output.contains("Analysis result"));
            // Should NOT contain separator for single target
            assertFalse(output.contains("=".repeat(80)));

        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void testEmptyTargetsList() throws Exception {
        RunResult res = Util.run("status");
        assertEquals(1, res.exitCode());
        System.err.println(res);
        assertTrue(res.out().contains("Available JVMs"));
    }
}