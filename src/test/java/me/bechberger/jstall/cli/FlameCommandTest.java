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
        RunCommandUtil.run("flame").output().startsWith("""
                Usage: jstall flame [-hV] [--output=<outputFile>] [--duration=<duration>]
                                    [--event=<event>] [--interval=<interval>] [--open] [<target>]
                Generate a flamegraph of the application using async-profiler
                      [<target>]               PID or filter (filters JVMs by main class name)
                  -d, --duration=<duration>    Profiling duration (default: 10s), default is 10s
                  -e, --event=<event>          Profiling event (default: cpu). Options: cpu,
                                               alloc, lock, wall, itimer
                  -h, --help                   Show this help message and exit.
                  -i, --interval=<interval>    Sampling interval (default: 10ms), default is
                                               10ms
                  -o, --output=<outputFile>    Output HTML file (default: flame.html)
                      --open                   Automatically open the generated HTML file in
                                               browser
                  -V, --version                Print version information and exit.
                
                Examples:
                  jstall flame 12345 --output flame.html --duration 15s
                  # Allocation flamegraph for a JVM running MyAppMainClass with a 20s duration
                  # open flamegraph automatically after generation
                  jstall flame MyAppMainClass --event alloc --duration 20s --open
                
                Available JVMs:
                """);
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
                    "DeadlockTestApp"
            );

            // Should resolve to the single matching JVM
            result.output().contains("DeadlockTestApp").contains(String.valueOf(pid));

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