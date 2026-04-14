package me.bechberger.jstall.cli;

import me.bechberger.femtocli.annotations.Command;
import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.impl.DependencyTreeAnalyzer;

import java.util.Map;

/**
 * Shows thread dependencies by analyzing which threads wait on locks held by other threads.
 */
@Command(
    name = "dependency-graph",
    description = "Show thread dependencies"
)
public class DependencyGraphCommand extends BaseAnalyzerCommand {

    @Override
    protected Analyzer getAnalyzer() {
        return new DependencyTreeAnalyzer();
    }

    @Override
    protected Map<String, Object> getAdditionalOptions() {
        return Map.of("graph-format", true);
    }
}