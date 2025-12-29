package me.bechberger.jstall.integration;

import me.bechberger.jstall.analyzer.impl.MostWorkAnalyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.testframework.TestAppLauncher;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.parser.ThreadDumpParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for MostWorkAnalyzer using live test applications.
 */
public class MostWorkAnalyzerIntegrationTest {

    @Test
    public void testBusyThreadDetection() throws Exception {
        TestAppLauncher launcher = new TestAppLauncher();

        try {
            // Launch app with busy workers
            launcher.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "busy-work");
            launcher.waitUntilReady(5000);

            // Wait for threads to start working
            Thread.sleep(1000);

            // Capture multiple dumps
            List<String> dumpContents = launcher.captureMultipleThreadDumps(3, 500);
            List<ThreadDump> dumps = new ArrayList<>();
            for (String content : dumpContents) {
                dumps.add(ThreadDumpParser.parse(content));
            }
            // Analyze
            MostWorkAnalyzer analyzer = new MostWorkAnalyzer();
            AnalyzerResult result = analyzer.analyze(dumps, Map.of("top", 3));

            // Verify
            assertEquals(0, result.exitCode());
            assertFalse(result.output().isBlank());
            assertTrue(result.output().contains("BusyWorker") || result.output().contains("threads"),
                "Should identify busy worker threads");

            System.out.println("Most work analysis:");
            System.out.println(result.output());
        } finally {
            launcher.stop();
        }
    }

    @Test
    public void testTopNOption() throws Exception {
        TestAppLauncher launcher = new TestAppLauncher();

        try {
            launcher.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "busy-work");
            launcher.waitUntilReady(5000);
            Thread.sleep(1000);

            List<String> dumpContents = launcher.captureMultipleThreadDumps(3, 500);
            List<ThreadDump> dumps = new ArrayList<>();
            for (String content : dumpContents) {
                dumps.add(ThreadDumpParser.parse(content));
            }
            // Test with top=2
            MostWorkAnalyzer analyzer = new MostWorkAnalyzer();
            AnalyzerResult result = analyzer.analyze(dumps, Map.of("top", 2));

            assertEquals(0, result.exitCode());
            assertFalse(result.output().isBlank());

            System.out.println("Top 2 threads:");
            System.out.println(result.output());
        } finally {
            launcher.stop();
        }
    }
}