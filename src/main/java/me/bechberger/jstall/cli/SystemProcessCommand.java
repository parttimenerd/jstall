package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.impl.DeadLockAnalyzer;
import me.bechberger.jstall.analyzer.impl.SystemProcessAnalyzer;
import picocli.CommandLine.Command;

/**
 * Detects deadlocks in thread dumps.
 */
@Command(
    name = "processes",
    description = "Detect other processes running on the system that consume high CPU time",
    mixinStandardHelpOptions = true
)
public class SystemProcessCommand extends BaseAnalyzerCommand {

    @Override
    protected Analyzer getAnalyzer() {
        return new SystemProcessAnalyzer();
    }
}