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
                Usage: jstall [-hV] [--file=<replayFile>] [COMMAND]
                One-shot JVM inspection tool
                  -f, --file=<replayFile>    File path for replay mode (replay ZIP file created
                                             by record command)
                  -h, --help                 Show this help message and exit.
                  -V, --version              Print version information and exit.
                Commands:
                  record                Record all data into a zip for later analysis
                  status                Run multiple analyzers over thread dumps (default command)
                  deadlock              Detect JVM-reported thread deadlocks
                  most-work             Identify threads doing the most work across dumps
                  flame                 Generate a flamegraph of the application using async-profiler
                  threads               List all threads sorted by CPU time
                  waiting-threads       Identify threads waiting without progress (potentially starving)
                  dependency-graph      Show thread dependencies (which threads wait on locks held by others)
                  vm-vitals             Show VM.vitals (if available)
                  gc-heap-info          Show GC.heap_info last absolute values and change
                  vm-classloader-stats  Show VM.classloader_stats grouped by classloader type
                  vm-metaspace          Show VM.metaspace summary and trend
                  compiler-queue        Analyze compiler queue state showing active compilations and queued tasks
                  ai                    AI-powered thread dump analysis using LLM
                  list                  List running JVM processes (excluding this tool)
                  processes             Detect other processes running on the system that consume high CPU time
                  jvm-support           Check whether the target JVM is likely still supported (based on java.version.date)
                """, run("--help").out());
    }

    @Test
    public void testDeadlockHelp() {
        assertEquals(
            "Usage: jstall deadlock [-hV] [--dumps=<dumps>] [--interval=<interval>] [--keep]\n" +
            "                       [--intelligent-filter] [--full] [<targets>...]\n" +
            "Detect JVM-reported thread deadlocks\n" +
            "      [<targets>...]       PID, 'all', filter or dump files (or replay ZIP as\n" +
            "                           first argument)\n" +
            "      --dumps=<dumps>      Number of dumps to collect, default is none\n" +
            "      --full               Run all analyses including expensive ones (only for\n" +
            "                           status command)\n" +
            "  -h, --help               Show this help message and exit.\n" +
            "      --intelligent-filter Use intelligent stack trace filtering (collapses\n" +
            "                           internal frames, focuses on application code)\n" +
            "      --interval=<interval>\n" +
            "                           Interval between dumps, default is 5s\n" +
            "      --keep               Persist dumps to disk\n" +
            "  -V, --version            Print version information and exit.\n",
            run("deadlock", "--help").out());
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "deadlock", "most-work", "flame", "threads", "waiting-threads", "dependency-graph", "compiler-queue", "vm-classloader-stats", "vm-metaspace", "jvm-support", "ai", "ai full"})
    public void smokeTestHelpTest(String cmd) {
        var args = cmd.split(" ");
        final var argsWithHelp = Arrays.copyOf(args, args.length + 1);
        argsWithHelp[args.length] = "--help";
        RunResult result = run(argsWithHelp);
        assertEquals(0, result.exitCode(), () -> "Unexpected exit code. Message: " + result.err());
        assertTrue(result.out().contains("Usage: "));
    }

}