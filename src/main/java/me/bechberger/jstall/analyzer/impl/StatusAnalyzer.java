package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.BaseAnalyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
import me.bechberger.jstall.runner.AnalyzerRunner;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Meta-analyzer that runs multiple analyzers in sequence.
 *
 * Combines the results of DeadLockAnalyzer, MostWorkAnalyzer, ThreadsAnalyzer, and DependencyGraphAnalyzer.
 */
public class StatusAnalyzer extends BaseAnalyzer {


    private final List<? extends Analyzer> ANALYZERS = List.of(
        new DeadLockAnalyzer(),
        new MostWorkAnalyzer(),
        new ThreadsAnalyzer(),
        new DependencyGraphAnalyzer(),
        new SystemProcessAnalyzer()
    );

    @Override
    public String name() {
        return "status";
    }

    @Override
    public Set<String> supportedOptions() {
        // Supports all options from constituent analyzers

        return ANALYZERS.stream()
            .flatMap(analyzer -> analyzer.supportedOptions().stream())
            .collect(java.util.stream.Collectors.toSet());
    }

    @Override
    public DumpRequirement dumpRequirement() {
        // Needs multiple dumps for MostWorkAnalyzer
        return DumpRequirement.MANY;
    }

    @SuppressWarnings("unchecked")
    @Override
    public AnalyzerResult analyze(List<ThreadDumpSnapshot> dumps, Map<String, Object> options) {
        AnalyzerRunner runner = new AnalyzerRunner();

        var runResult = runner.runAnalyzers((List<Analyzer>) ANALYZERS, dumps, options);

        return AnalyzerResult.withExitCode(runResult.output(), runResult.exitCode());
    }
}