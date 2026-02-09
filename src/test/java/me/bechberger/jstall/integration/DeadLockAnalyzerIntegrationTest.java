package me.bechberger.jstall.integration;

import me.bechberger.jstall.analyzer.impl.DeadLockAnalyzer;
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
 * Integration test for DeadLockAnalyzer using live test applications.
 */
public class DeadLockAnalyzerIntegrationTest {

    public void testDeadlockDetectionWithLiveApp() throws Exception {
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

            DeadLockAnalyzer analyzer = new DeadLockAnalyzer();
            AnalyzerResult result = analyzer.analyze(List.of(new ThreadDumpSnapshot(dump, dumpContent, null, null)), Map.of());

            // Verify deadlock was detected
            assertEquals(2, result.exitCode(), "Expected exit code 2 for deadlock");
            assertTrue(result.output().contains("Deadlock") || result.output().contains("deadlock"),
                "Output should mention deadlock");

            System.out.println("Deadlock detection output:");
            System.out.println(result.output());
        } finally {
            launcher.stop();
        }
    }

    @Test
    public void testNoDeadlockWithNormalApp() throws Exception {
        TestAppLauncher launcher = new TestAppLauncher();

        try {
            // Launch normal app
            launcher.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "normal");
            launcher.waitUntilReady(5000);

            Thread.sleep(500);

            // Capture thread dump
            String dumpContent = launcher.captureThreadDump();
            ThreadDump dump = ThreadDumpParser.parse(dumpContent);
            DeadLockAnalyzer analyzer = new DeadLockAnalyzer();
            AnalyzerResult result = analyzer.analyze(List.of(new ThreadDumpSnapshot(dump, dumpContent, null, null)), Map.of());

            // Verify no deadlock
            assertEquals(0, result.exitCode(), "Expected exit code 0 for no deadlock");
            System.out.println("No deadlock output:");
            System.out.println(result.output());
        } finally {
            launcher.stop();
        }
    }
}