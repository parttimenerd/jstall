package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.BaseAnalyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jstall.provider.requirement.DataRequirements;
import me.bechberger.jstall.runner.AnalyzerRunner;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Meta-analyzer that runs multiple analyzers in sequence.
 * <p>
 * Combines the results of DeadLockAnalyzer, MostWorkAnalyzer, ThreadsAnalyzer, and DependencyTreeAnalyzer.
 */
public class StatusAnalyzer extends BaseAnalyzer {


    private final List<Analyzer> ANALYZERS = List.of(
        new VmVitalsAnalyzer(),
        new GcHeapInfoAnalyzer(),
        new VmClassloaderStatsAnalyzer(),
        new VmMetaspaceAnalyzer(),
        new CompilerQueueAnalyzer(),
        new DeadLockAnalyzer(),
        new MostWorkAnalyzer(),
        new ThreadsAnalyzer(),
        new DependencyTreeAnalyzer(),
        new SystemProcessAnalyzer(),
        new JvmSupportAnalyzer()
    );

    private final List<Analyzer> EXPENSIVE_ANALYZERS = List.of(
        new ClassHistogramDiffAnalyzer()
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

    private List<Analyzer> getAnalyzers(Map<String, Object> options) {
        List<Analyzer> analyzers = ANALYZERS;
        if (getBooleanOption(options, "full", false)) {
            analyzers = new java.util.ArrayList<>(ANALYZERS);
            analyzers.addAll(EXPENSIVE_ANALYZERS);
        }
        return analyzers;
    }

    @Override
    public DataRequirements getDataRequirements(Map<String, Object> options) {
        DataRequirements merged = DataRequirements.empty();
        for (Analyzer analyzer : getAnalyzers(options)) {
            merged = merged.merge(analyzer.getDataRequirements(options));
        }
        return merged.merge(DataRequirements.builder().addJcmdOnce("VM.uptime").build());
    }

    @Override
    public AnalyzerResult analyze(ResolvedData data, Map<String, Object> options) {
        AnalyzerRunner runner = new AnalyzerRunner();

        var runResult = runner.runAnalyzers(getAnalyzers(options), data, options);

        StringBuilder output = new StringBuilder();
        String uptime = resolveVmUptime(data);
        if (uptime != null) {
            output.append("VM uptime: ").append(uptime).append("\n");
        }
        if (!runResult.output().isBlank()) {
            if (!output.isEmpty()) {
                output.append("\n");
            }
            output.append(runResult.output());
        }

        return AnalyzerResult.withExitCode(output.toString().trim(), runResult.exitCode());
    }

    private String resolveVmUptime(ResolvedData data) {
        List<CollectedData> samples = data.collectedData("vm-uptime");
        if (samples.isEmpty()) {
            return null;
        }
        String raw = samples.get(samples.size() - 1).rawData();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<String> lines = raw.lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .toList();
        if (lines.isEmpty()) {
            return null;
        }
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (!line.matches("\\d+:")) {
                return line;
            }
        }
        return lines.get(lines.size() - 1);
    }
}