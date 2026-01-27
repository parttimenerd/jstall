package me.bechberger.jstall.cli;

import me.bechberger.jstall.Main;
import me.bechberger.minicli.MiniCli;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class HelpTest {

    private String run(String... args) {
        return MiniCli.builder()
                .commandConfig(cfg -> {
                    cfg.version = "0.4.5";
                    cfg.mixinStandardHelpOptions = true;
                    cfg.defaultValueHelpTemplate = ", default is ${DEFAULT-VALUE}";
                    cfg.defaultValueOnNewLine = false;
                })
                .runCaptured(new Main(), args).out();
    }

    @Test
    public void testMainHelp() {
        assertEquals("""
                Usage: jstall [-hV] [COMMAND]
                One-shot JVM inspection tool
                  -h, --help       Show this help message and exit.
                  -V, --version    Print version information and exit.
                Commands:
                  jstall status            Run multiple analyzers over thread dumps (default command)
                  jstall deadlock          Detect JVM-reported thread deadlocks
                  jstall most-work         Identify threads doing the most work across dumps
                  jstall flame             Generate a flamegraph of the application using async-profiler
                  jstall threads           List all threads sorted by CPU time
                  jstall waiting-threads   Identify threads waiting without progress (potentially starving)
                  jstall dependency-graph  Show thread dependencies (which threads wait on locks held by others)
                  jstall ai                AI-powered thread dump analysis using LLM
                  jstall ai full           Analyze all JVMs on the system with AI
                  jstall list              List running JVM processes (excluding this tool)
                  jstall processes         Detect other processes running on the system that consume high CPU time
                """, run("--help"));
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
                """, run("deadlock", "--help"));
    }
}