package me.bechberger.jstall.testframework;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Generator for test thread dumps.
 *
 * Run this test to generate sample thread dumps for use in other tests.
 * The dumps are saved to src/test/resources/thread-dumps/
 */
public class ThreadDumpGeneratorTest {

    private static final Path TEST_RESOURCES = Paths.get("src/test/resources/thread-dumps");

    @Test
    public void generateDeadlockDump() throws Exception {
        System.out.println("Generating deadlock thread dump...");

        TestAppLauncher launcher = new TestAppLauncher();
        try {
            launcher.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "deadlock");
            launcher.waitUntilReady(5000);

            // Wait for deadlock to establish
            Thread.sleep(1000);

            String dump = launcher.captureThreadDump();
            launcher.saveThreadDump(dump, TEST_RESOURCES.resolve("deadlock.txt"));

            System.out.println("✓ Deadlock dump generated");
        } finally {
            launcher.stop();
        }
    }

    @Test
    public void generateBusyWorkDumps() throws Exception {
        System.out.println("Generating busy work thread dumps...");

        TestAppLauncher launcher = new TestAppLauncher();
        try {
            launcher.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "busy-work");
            launcher.waitUntilReady(5000);

            // Wait for threads to start working
            Thread.sleep(1000);

            List<String> dumps = launcher.captureMultipleThreadDumps(3, 1000);
            launcher.saveThreadDumps(dumps, TEST_RESOURCES, "busy-work");

            System.out.println("✓ Busy work dumps generated (" + dumps.size() + " dumps)");
        } finally {
            launcher.stop();
        }
    }

    @Test
    public void generateNormalDumps() throws Exception {
        System.out.println("Generating normal thread dumps...");

        TestAppLauncher launcher = new TestAppLauncher();
        try {
            launcher.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "normal");
            launcher.waitUntilReady(5000);

            // Wait for threads to stabilize
            Thread.sleep(500);

            List<String> dumps = launcher.captureMultipleThreadDumps(3, 500);
            launcher.saveThreadDumps(dumps, TEST_RESOURCES, "normal");

            System.out.println("✓ Normal dumps generated (" + dumps.size() + " dumps)");
        } finally {
            launcher.stop();
        }
    }

    /**
     * Generate all dumps at once.
     */
    @Test
    public void generateAllDumps() throws Exception {
        System.out.println("=== Generating all test thread dumps ===\n");

        generateDeadlockDump();
        Thread.sleep(500);

        generateBusyWorkDumps();
        Thread.sleep(500);

        generateNormalDumps();

        System.out.println("\n=== All dumps generated successfully ===");
        System.out.println("Location: " + TEST_RESOURCES.toAbsolutePath());
    }
}