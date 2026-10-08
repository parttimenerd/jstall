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
        assert output.contains("Best first check") : "top-level help should present status as the starting point";
        assert output.contains("SapMachine-only") : "top-level help should clarify vm-vitals availability";
        // Help output shows either "Commands:" (normal mode) or "Available commands:" (remote -s mode)
        assert output.contains("Commands:") || output.contains("Available commands:");
    }

    @Test
    public void testNoArgsLandingPageUsesImprovedDescriptions() {
        var output = run().hasNoError().get().out();
        assert output.contains("Best first check") : "landing page should present status as the starting point";
        assert output.contains("SapMachine-only") : "landing page should clarify vm-vitals availability";
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

    @Test
    public void testColorOptionInHelp() {
        var output = run("status", "--help").hasNoError().get().out();
        assert output.contains("--color") : "status help should show --color option";
    }

    @Test
    public void testStatusHelpUsesBeginnerFriendlyDescription() {
        var output = run("status", "--help").hasNoError().get().out();
        assert output.contains("Best first check") : "status help should guide first-time users";
        assert output.contains("--top=<top>") : "status help should still include top option";
    }

    @Test
    public void testVmVitalsHelpExplainsSapMachineAndAllRowsOption() {
        var output = run("vm-vitals", "--help").hasNoError().get().out();
        assert output.contains("SapMachine") : "vm-vitals help should explain JVM support clearly";
        assert output.contains("-1 = all") : "vm-vitals help should explain how to show all rows";
    }
}