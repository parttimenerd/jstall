package me.bechberger.jstall.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;

class JMXDiagnosticHelperHistogramCaptureTest {

    @Test
    void canGrabClassHistogramFromAnotherJvm() throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");

        Process p = new ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            HelperApp.class.getName()
        ).redirectErrorStream(true).start();

        try {
            long pid;
            // Wait for the helper to print its PID
            String line;
            try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                pid = -1;
                while (System.nanoTime() < deadline && (line = br.readLine()) != null) {
                    if (line.startsWith("PID=")) {
                        pid = Long.parseLong(line.substring("PID=".length()).trim());
                        break;
                    }
                }
            }

            if (pid <= 0) {
                throw new IllegalStateException("HelperApp did not print PID");
            }

            String out = JMXDiagnosticHelper.executeCommand(pid, "gcClassHistogram", "GC.class_histogram");
            assertFalse(out.isBlank());
            assertFalse(out.lines().limit(50).noneMatch(l -> l.contains("#instances") || l.contains("class name")));
        } finally {
            p.destroy();
            p.waitFor(2, TimeUnit.SECONDS);
            p.destroyForcibly();
        }
    }

    /** Small helper JVM to attach to during tests. */
    public static class HelperApp {
        public static void main(String[] args) throws Exception {
            System.out.println("PID=" + ProcessHandle.current().pid());
            System.out.flush();
            // Keep running so we can attach.
            Thread.sleep(60_000);
        }
    }
}