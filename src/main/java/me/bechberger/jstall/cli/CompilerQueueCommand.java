package me.bechberger.jstall.cli;

import me.bechberger.femtocli.annotations.Command;
import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.impl.CompilerQueueAnalyzer;

/**
 * Command for analyzing compiler queue state and trending.
 * <p>
 * Shows active compilations and queued compilation tasks over time.
 */
@Command(
    name = "compiler-queue",
    description = "Analyze compiler queue state showing active compilations and queued tasks"
)
public class CompilerQueueCommand extends BaseAnalyzerCommand {

    @Override
    protected Analyzer getAnalyzer() {
        return new CompilerQueueAnalyzer();
    }
}
