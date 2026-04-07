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
        run("deadlock", "--help").hasNoError().output().isEqualTo(
            """
            Usage: jstall deadlock [-hV] [--dumps=<dumps>] [--interval=<interval>] [--keep]
                                   [--intelligent-filter] [--full] [<targets>...]
            Detect JVM-reported thread deadlocks
                  [<targets>...]       PID, 'all', filter or dump files (or replay ZIP as
                                       first argument)
                  --dumps=<dumps>      Number of dumps to collect, default is none
                  --full               Run all analyses including expensive ones (only for
                                       status command)
              -h, --help               Show this help message and exit.
                  --intelligent-filter Use intelligent stack trace filtering (collapses
                                       internal frames, focuses on application code)
                  --interval=<interval>
                                       Interval between dumps, default is 5s
                  --keep               Persist dumps to disk
              -V, --version            Print version information and exit.
            """
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "deadlock", "most-work", "flame", "threads", "waiting-threads", "dependency-tree", "compiler-queue", "vm-classloader-stats", "vm-metaspace", "jvm-support", "ai", "ai full"})
    public void smokeTestHelpTest(String cmd) {
        var args = cmd.split(" ");
        final var argsWithHelp = Arrays.copyOf(args, args.length + 1);
        argsWithHelp[args.length] = "--help";
        run(argsWithHelp).hasNoError().hasOutputContaining("Usage: ");
    }

}