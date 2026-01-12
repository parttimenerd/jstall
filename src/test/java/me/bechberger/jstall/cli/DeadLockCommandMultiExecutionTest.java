package me.bechberger.jstall.cli;

import me.bechberger.jstall.testframework.TestAppLauncher;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for DeadLockCommand with multi-execution support.
 */
class DeadLockCommandMultiExecutionTest {

    @Test
    @Disabled
    void testDeadLockCommandWithFilterMatchingMultiple() throws Exception {
        TestAppLauncher launcher1 = new TestAppLauncher();
        TestAppLauncher launcher2 = new TestAppLauncher();

        try {
            // Launch two apps with deadlocks
            launcher1.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "deadlock");
            launcher2.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "deadlock");

            launcher1.waitUntilReady(5000);
            launcher2.waitUntilReady(5000);
            Thread.sleep(1000); // Wait for deadlocks to establish

            long pid1 = launcher1.getPid();
            long pid2 = launcher2.getPid();

            // Use filter to match both
            DeadLockCommand cmd = new DeadLockCommand();
            cmd.targets = List.of("DeadlockTestApp");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            System.setOut(new PrintStream(out));

            try {
                int exitCode = cmd.call();

                // Should detect deadlocks
                assertEquals(2, exitCode, "Should return exit code 2 for deadlock");

                String output = out.toString();

                // Should analyze multiple JVMs
                assertTrue(output.contains("=".repeat(80)));

                // Both should have deadlocks
                long deadlockCount = output.lines()
                    .filter(line -> line.toLowerCase().contains("deadlock"))
                    .count();
                assertTrue(deadlockCount >= 2, "Should detect deadlocks in both JVMs");

                // PIDs should be sorted
                int pos1 = output.indexOf("PID " + Math.min(pid1, pid2));
                int pos2 = output.indexOf("PID " + Math.max(pid1, pid2));
                assertTrue(pos1 > 0 && pos2 > 0);
                assertTrue(pos1 < pos2, "PIDs should be sorted");

            } finally {
                System.setOut(System.out);
            }

        } finally {
            launcher1.stop();
            launcher2.stop();
        }
    }
}