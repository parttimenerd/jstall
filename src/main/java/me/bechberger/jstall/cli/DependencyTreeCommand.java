package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.impl.DependencyTreeAnalyzer;
import me.bechberger.femtocli.annotations.Command;

/**
 * Shows thread dependencies by analyzing which threads wait on locks held by other threads.
 */
@Command(
    name = "dependency-tree",
    description = "Show non deadlock thread dependencies over time"
)
public class DependencyTreeCommand extends BaseAnalyzerCommand {

    @Override
    protected Analyzer getAnalyzer() {
        return new DependencyTreeAnalyzer();
    }
}