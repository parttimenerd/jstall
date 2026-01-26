package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.impl.SystemProcessAnalyzer;
import me.bechberger.minicli.annotations.Command;

/**
 * Detects other processes consuming CPU.
 */
@Command(
    name = "processes",
    description = "Detect other processes running on the system that consume high CPU time"
)
public class SystemProcessCommand extends BaseAnalyzerCommand {

    @Override
    protected Analyzer getAnalyzer() {
        return new SystemProcessAnalyzer();
    }
}