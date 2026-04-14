package me.bechberger.jstall.integration;

import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.analyzer.impl.DependencyGraphAnalyzer;
import me.bechberger.jstall.analyzer.impl.DependencyTreeAnalyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
import me.bechberger.jstall.testframework.TestAppLauncher;
import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.parser.ThreadDumpParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test to verify that DependencyGraphAnalyzer and DependencyTreeAnalyzer
 * produce distinct outputs (not identical copies of each other).
 *
 * This test was added to catch a bug where both analyzers had been accidentally
 * given the same tree-based implementation, resulting in duplicate output for the
 * same input.
 */
public class DependencyAnalyzersRegressionTest {

    /**
     * Verify that dependency-graph and dependency-tree commands produce different output
     * when analyzing the same deadlock scenario.
     *
     * CRITICAL REGRESSION: If both analyzers produce identical output, it indicates
     * that DependencyGraphAnalyzer was mistakenly given tree-style implementation code
     * instead of its own graph-style implementation.
     */
    @Test
    public void testGraphAndTreeProduceDifferentOutput() throws Exception {
        TestAppLauncher launcher = new TestAppLauncher();

        try {
            // Launch app with deadlock
            launcher.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "deadlock");
            launcher.waitUntilReady(5000);

            // Wait for deadlock to establish
            Thread.sleep(1000);

            // Capture thread dump
            String dumpContent = launcher.captureThreadDump();
            ThreadDump dump = ThreadDumpParser.parse(dumpContent);
            List<ThreadDumpSnapshot> dumps = List.of(new ThreadDumpSnapshot(dump, dumpContent, null, null));
            ResolvedData data = ResolvedData.fromDumps(dumps);

            // Run both analyzers
            DependencyGraphAnalyzer graphAnalyzer = new DependencyGraphAnalyzer();
            DependencyTreeAnalyzer treeAnalyzer = new DependencyTreeAnalyzer();

            AnalyzerResult graphResult = graphAnalyzer.analyze(data, Map.of());
            AnalyzerResult treeResult = treeAnalyzer.analyze(data, Map.of());

            String graphOutput = graphResult.output();
            String treeOutput = treeResult.output();

            System.out.println("=== Dependency Graph Output ===");
            System.out.println(graphOutput.isEmpty() ? "(empty)" : graphOutput);
            System.out.println("\n=== Dependency Tree Output ===");
            System.out.println(treeOutput.isEmpty() ? "(empty)" : treeOutput);

            // Assert outputs are NOT identical (main regression check)
            // This is the critical regression test: both commands should NOT produce the same output
            assertNotEquals(graphOutput, treeOutput,
                "dependency-graph and dependency-tree should produce different output. " +
                "If they are identical, it indicates both analyzers have the same (likely wrong) implementation.");

            // Verify tree output includes deadlock cycle detection or bottleneck analysis
            assertTrue(treeOutput.contains("Deadlock") || treeOutput.contains("bottleneck") ||
                       treeOutput.contains("blocking threads"),
                "dependency-tree output should identify deadlock or bottleneck structure; got: " + treeOutput);

        } finally {
            launcher.stop();
        }
    }
}
