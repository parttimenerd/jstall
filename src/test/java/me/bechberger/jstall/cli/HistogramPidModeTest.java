package me.bechberger.jstall.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static me.bechberger.jstall.cli.Util.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistogramPidModeTest {

    @Test
    void histogramPidModeCapturesAndDiffs() throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");

        Process p = new ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            HelperApp.class.getName()
        ).redirectErrorStream(true).start();

        long pid;
        try {
            // Wait for PID line
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

            var r = run("histogram", String.valueOf(pid), "--top", "3", "--sort", "bytes");
            assertEquals(0, r.exitCode());
            assertTrue(r.out().contains("deltas"));
        } finally {
            p.destroy();
            p.waitFor(2, TimeUnit.SECONDS);
            p.destroyForcibly();
        }
    }

    public static class HelperApp {
        public static void main(String[] args) throws Exception {
            System.out.println("PID=" + ProcessHandle.current().pid());
            System.out.flush();
            // allocate a bit in a loop so second capture differs
            byte[][] junk = new byte[256][];
            int i = 0;
            while (true) {
                junk[i++ % junk.length] = new byte[1024 * 10];
                Thread.sleep(10);
            }
        }
    }
}