package me.bechberger.jstall.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that JMXDiagnosticHelper construction completes within a bounded time
 * even when VirtualMachine.attach() hangs (e.g. Gradle daemon on SAP JDK).
 */
class JMXDiagnosticHelperAttachTimeoutTest {

    /**
     * Verifies that attach completes quickly and that jcmd fallback works.
     * Spawns a child JVM as the target so we can legitimately attach to it.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void constructorAndCommandCompleteWithinTimeout() throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");

        Process child = new ProcessBuilder(java,
            "-cp", System.getProperty("java.class.path"),
            SleeperApp.class.getName())
            .redirectErrorStream(true)
            .start();

        try {
            long childPid = -1;
            try (var br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(child.getInputStream()))) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline) {
                    String line = br.readLine();
                    if (line != null && line.matches("\\d+")) {
                        childPid = Long.parseLong(line.trim());
                        break;
                    }
                }
            }
            if (childPid < 0) fail("Child process did not print its PID in time");

            CommandExecutor executor = new CommandExecutor.LocalCommandExecutor();
            long start = System.currentTimeMillis();

            // Constructor must not block forever — 5s attach timeout then jcmd fallback
            JMXDiagnosticHelper helper = new JMXDiagnosticHelper(executor, childPid);
            long attachElapsed = System.currentTimeMillis() - start;

            assertTrue(attachElapsed < 10_000,
                "JMXDiagnosticHelper constructor took " + attachElapsed + "ms; should be < 10s");

            // Command must succeed via JMX or jcmd fallback
            String uptime = helper.executeCommand("VM.uptime");
            assertTrue(uptime != null && !uptime.isBlank(), "VM.uptime should return output");

            helper.cleanup();
        } finally {
            child.destroyForcibly();
        }
    }

    /** Minimal helper app: prints its PID then sleeps so we can attach to it. */
    static class SleeperApp {
        public static void main(String[] args) throws Exception {
            System.out.println(ProcessHandle.current().pid());
            System.out.flush();
            Thread.sleep(30_000);
        }
    }
}
