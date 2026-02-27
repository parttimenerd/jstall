package me.bechberger.jstall.cli;

import me.bechberger.femtocli.RunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static me.bechberger.jstall.cli.Util.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HelpTest {

    @Test
    public void testMainHelp() {
        assertEquals("""
                Usage: jstall [-hV] [COMMAND]
                One-shot JVM inspection tool
                  -h, --help       Show this help message and exit.
                  -V, --version    Print version information and exit.
                Commands:
                  status            Run multiple analyzers over thread dumps (default command)
                  deadlock          Detect JVM-reported thread deadlocks
                  most-work         Identify threads doing the most work across dumps
                  flame             Generate a flamegraph of the application using async-profiler
                  threads           List all threads sorted by CPU time
                  waiting-threads   Identify threads waiting without progress (potentially starving)
                  dependency-graph  Show thread dependencies (which threads wait on locks held by others)
                  ai                AI-powered thread dump analysis using LLM
                  list              List running JVM processes (excluding this tool)
                  processes         Detect other processes running on the system that consume high CPU time
                  jvm-support       Check whether the target JVM is likely still supported (based on java.version.date)
                """, run("--help").out());
    }

    @Test
    public void testDeadlockHelp() {
        assertEquals("""
                Usage: jstall deadlock [-hV] [--dumps=<dumps>] [--interval=<interval>] [--keep]
                                       [--intelligent-filter] [<targets>...]
                Detect JVM-reported thread deadlocks
                      [<targets>...]       PID, filter or dump files
                      --dumps=<dumps>      Number of dumps to collect, default is none
                  -h, --help               Show this help message and exit.
                      --intelligent-filter Use intelligent stack trace filtering (collapses
                                           internal frames, focuses on application code)
                      --interval=<interval>
                                           Interval between dumps, default is 5s
                      --keep               Persist dumps to disk
                  -V, --version            Print version information and exit.
                """, run("deadlock", "--help").out());
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "deadlock", "most-work", "flame", "threads", "waiting-threads", "dependency-graph", "jvm-support", "ai", "ai full"})
    public void smokeTestHelpTest(String cmd) {
        var args = cmd.split(" ");
        final var argsWithHelp = Arrays.copyOf(args, args.length + 1);
        argsWithHelp[args.length] = "--help";
        RunResult result = run(argsWithHelp);
        assertEquals(0, result.exitCode(), () -> "Unexpected exit code. Message: " + result.err());
        assertTrue(result.out().contains("Usage: "));
    }

}