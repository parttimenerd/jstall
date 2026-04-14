package me.bechberger.jstall.cli;

// Use the fluent RunResultAssert returned by RunCommandUtil.run
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static me.bechberger.jstall.cli.RunCommandUtil.run;
// Use RunResultAssert fluent assertions from RunCommandUtil.run

public class HelpTest {

    @Test
    public void testMainHelp() {
        var output = run("--help").hasNoError().get().out();
        assert output.contains("Usage: jstall");
        assert output.contains("status");
        assert output.contains("deadlock");
        // Help output shows either "Commands:" (normal mode) or "Available commands:" (remote -s mode)
        assert output.contains("Commands:") || output.contains("Available commands:");
    }

    @Test
    public void testDeadlockHelp() {
        var output = run("deadlock", "--help").hasNoError().get().out();
        assert output.contains("Usage: jstall deadlock");
        assert output.contains("--dump-count=<count>");
        assert output.contains("--interval=<interval>");
        assert output.contains("--file=<replayFile>");
        assert output.contains("-f, --file=<replayFile>");
        assert output.contains("Detect JVM-reported thread deadlocks");
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "deadlock", "most-work", "flame", "threads", "waiting-threads", "dependency-graph", "compiler-queue", "vm-classloader-stats", "vm-metaspace", "jvm-support", "ai", "ai full"})
    public void smokeTestHelpTest(String cmd) {
        var args = cmd.split(" ");
        final var argsWithHelp = Arrays.copyOf(args, args.length + 1);
        argsWithHelp[args.length] = "--help";
        run(argsWithHelp).hasNoError().hasOutputContaining("Usage: ");
    }

}