package me.bechberger.jstall.cli;

import me.bechberger.jstall.testframework.TestAppLauncher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FlameCommand - verifying it supports filtering but only for a single JVM.
 */
class FlameCommandTest {

    @Test
    void testFlameCommandWithoutTarget() {
                var output = RunCommandUtil.run("flame").get().out();
                assertThat(output).contains("Usage: jstall flame");
                assertThat(output).contains("Generate a flamegraph of the application using async-profiler");
                assertThat(output).contains("--duration=<duration>");
                assertThat(output).contains("--event=<event>");
                assertThat(output).contains("--interval=<interval>");
                assertThat(output).contains("--output=<outputFile>");
                assertThat(output).contains("Available JVMs:");
    }

    @Test
    void testFlameCommandWithFilterMatchingSingleJVM() throws Exception {
        TestAppLauncher launcher = new TestAppLauncher();

        try {
            launcher.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "normal");
            launcher.waitUntilReady(5000);

            long pid = launcher.getPid();

            var result = RunCommandUtil.run(
                    "flame",
                    "--duration=1s",
                    String.valueOf(pid)
            );

            // Should resolve to the single JVM by PID
            result.errorOutput().as("error output").doesNotContain("does not support multiple targets");
            // stdout should mention the PID (printed before profiling starts) or contain a profiling result/error
            String out = result.get().out();
            String err = result.get().err();
            assertThat(out + err).as("combined output").contains(String.valueOf(pid));

        } finally {
            launcher.stop();
        }
    }

    @Test
    void testFlameCommandRejectsMultipleMatches() throws Exception {
        TestAppLauncher launcher1 = new TestAppLauncher();
        TestAppLauncher launcher2 = new TestAppLauncher();

        try {
            // Launch two JVMs with same class name
            launcher1.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "normal");
            launcher2.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "busy-work");

            launcher1.waitUntilReady(5000);
            launcher2.waitUntilReady(5000);

            long pid1 = launcher1.getPid();
            long pid2 = launcher2.getPid();

            var result = RunCommandUtil.run("flame", "DeadlockTestApp");

            // Should fail
            result.hasError();

            result.errorOutput().contains("does not support multiple targets").contains("" + pid1).contains("" + pid2);

        } finally {
            launcher1.stop();
            launcher2.stop();
        }
    }

    @Test
    void testFlameCommandWithNonMatchingFilter() throws Exception {
        TestAppLauncher launcher = new TestAppLauncher();

        try {
            launcher.launch("me.bechberger.jstall.testapp.DeadlockTestApp", "normal");
            launcher.waitUntilReady(5000);

            var result = RunCommandUtil.run("flame", "NonExistentApp");

            // Should fail

            result.hasError().hasErrorContaining("No JVMs");
        } finally {
            launcher.stop();
        }
    }
}