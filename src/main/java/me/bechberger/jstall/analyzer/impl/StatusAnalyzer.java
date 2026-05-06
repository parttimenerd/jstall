package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.AnalyzerOutput;
import me.bechberger.jstall.analyzer.BaseAnalyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jstall.provider.requirement.DataRequirements;

import java.util.ArrayList;
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
        List<Analyzer> analyzers = getAnalyzers(options);
        int dumpCount = data.dumps().size();
        String uptime = resolveVmUptime(data);

        List<AnalyzerOutput.CompositeOutput.Section> sections = new ArrayList<>();
        int maxExitCode = 0;

        for (Analyzer sub : analyzers) {
            Map<String, Object> subOptions = filterOptions(sub, options);
            AnalyzerOutput sectionContent;

            if (sub.dumpRequirement() == DumpRequirement.MANY && dumpCount < 2) {
                // Not enough dumps yet — show placeholder
                sectionContent = new AnalyzerOutput.TextOutput("Collecting data... (need 2 samples)");
            } else {
                try {
                    ResolvedData subData = new ResolvedData(
                        filterDumps(sub, data.dumps()),
                        data.systemProperties(),
                        data.environment(),
                        data.collectedDataByType()
                    );
                    AnalyzerResult result = sub.analyze(subData, subOptions);
                    if (!result.shouldDisplay() || result.output().isBlank()) {
                        continue;
                    }
                    maxExitCode = Math.max(maxExitCode, result.exitCode());
                    sectionContent = result.structured();
                } catch (Exception e) {
                    sectionContent = new AnalyzerOutput.TextOutput("Error: " + e.getMessage());
                }
            }

            sections.add(new AnalyzerOutput.CompositeOutput.Section(sub.name(), sectionContent));
        }

        // Add uptime as preamble to first section or as its own section
        AnalyzerOutput output;
        if (sections.isEmpty()) {
            output = new AnalyzerOutput.TextOutput(uptime != null ? "VM uptime: " + uptime : "No data available");
        } else {
            if (uptime != null) {
                // Prepend uptime section
                sections.add(0, new AnalyzerOutput.CompositeOutput.Section("uptime",
                    new AnalyzerOutput.TextOutput("VM uptime: " + uptime)));
            }
            output = new AnalyzerOutput.CompositeOutput(sections);
        }

        return AnalyzerResult.withExitCode(output, maxExitCode);
    }

    /**
     * Builds a placeholder output showing all tabs with "Collecting data..." content.
     * Used in live mode to show the tab bar immediately before data collection starts.
     */
    public AnalyzerOutput buildPlaceholderOutput(Map<String, Object> options) {
        List<Analyzer> analyzers = getAnalyzers(options);
        List<AnalyzerOutput.CompositeOutput.Section> sections = new ArrayList<>();
        for (Analyzer sub : analyzers) {
            sections.add(new AnalyzerOutput.CompositeOutput.Section(sub.name(),
                new AnalyzerOutput.TextOutput("Collecting data...")));
        }
        return new AnalyzerOutput.CompositeOutput(sections);
    }

    private Map<String, Object> filterOptions(Analyzer analyzer, Map<String, Object> options) {
        Map<String, Object> filtered = new java.util.HashMap<>();
        for (String supportedOption : analyzer.supportedOptions()) {
            if (options.containsKey(supportedOption)) {
                filtered.put(supportedOption, options.get(supportedOption));
            }
        }
        return filtered;
    }

    private List<me.bechberger.jstall.model.ThreadDumpSnapshot> filterDumps(Analyzer analyzer,
            List<me.bechberger.jstall.model.ThreadDumpSnapshot> dumps) {
        return switch (analyzer.dumpRequirement()) {
            case ONE -> dumps.isEmpty() ? List.of() : List.of(dumps.get(0));
            case MANY -> dumps;
            case ANY -> dumps;
        };
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