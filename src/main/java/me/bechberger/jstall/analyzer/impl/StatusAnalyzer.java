package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.BaseAnalyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.model.ThreadDumpWithRaw;
import me.bechberger.jstall.runner.AnalyzerRunner;
import me.bechberger.jthreaddump.model.ThreadDump;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Meta-analyzer that runs multiple analyzers in sequence.
 *
 * Combines the results of DeadLockAnalyzer and MostWorkAnalyzer.
 */
public class StatusAnalyzer extends BaseAnalyzer {

    private static List<Class<? extends Analyzer>> ANALYZER_CLASSES = List.of(
        DeadLockAnalyzer.class,
        MostWorkAnalyzer.class
    );

    private List<? extends Analyzer> ANALYZERS = ANALYZER_CLASSES.stream()
        .map(cls -> {
            try {
                return cls.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Failed to instantiate analyzer: " + cls.getName(), e);
            }
        })
        .toList();

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
    public AnalyzerResult analyze(List<ThreadDumpWithRaw> dumps, Map<String, Object> options) {
        AnalyzerRunner runner = new AnalyzerRunner();

        var runResult = runner.runAnalyzers((List<Analyzer>) ANALYZERS, dumps, options);

        return AnalyzerResult.withExitCode(runResult.output(), runResult.exitCode());
    }
}