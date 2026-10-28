package me.bechberger.jstall.cli;

import me.bechberger.femtocli.annotations.Command;
import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.impl.DeadLockAnalyzer;

/**
 * Detects deadlocks in thread dumps.
 */
@Command(
    name = "deadlock",
    description = "Detect JVM-reported thread deadlocks"
)
public class DeadLockCommand extends BaseAnalyzerCommand {

    @Override
    protected Analyzer getAnalyzer() {
        return new DeadLockAnalyzer();
    }
}