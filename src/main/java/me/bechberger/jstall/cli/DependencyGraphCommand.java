package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.impl.DependencyGraphAnalyzer;
import picocli.CommandLine.Command;

/**
 * Shows thread dependencies by analyzing which threads wait on locks held by other threads.
 */
@Command(
    name = "dependency-graph",
    description = "Show thread dependencies (which threads wait on locks held by others)",
    mixinStandardHelpOptions = true
)
public class DependencyGraphCommand extends BaseAnalyzerCommand {

    @Override
    protected Analyzer getAnalyzer() {
        return new DependencyGraphAnalyzer();
    }
}