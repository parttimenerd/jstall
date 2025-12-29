package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.impl.DeadLockAnalyzer;
import picocli.CommandLine.Command;

/**
 * Detects deadlocks in thread dumps.
 */
@Command(
    name = "dead-lock",
    description = "Detect JVM-reported thread deadlocks"
)
public class DeadLockCommand extends BaseAnalyzerCommand {

    @Override
    protected Analyzer getAnalyzer() {
        return new DeadLockAnalyzer();
    }
}