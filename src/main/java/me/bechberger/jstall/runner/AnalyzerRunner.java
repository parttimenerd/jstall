package me.bechberger.jstall.runner;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jthreaddump.model.ThreadDump;

import java.util.*;

/**
 * Orchestrates the execution of analyzers.
 *
 * Responsibilities:
 * - Validate options per analyzer
 * - Filter dumps based on analyzer requirements
 * - Run analyzers in order
 * - Aggregate results and exit codes
 */
public class AnalyzerRunner {

    /**
     * Runs multiple analyzers in sequence.
     *
     * @param analyzers Analyzers to run (in order)
     * @param dumps Thread dumps to analyze
     * @param options All options provided by user
     * @return Aggregated result
     */
    public RunResult runAnalyzers(List<Analyzer> analyzers, List<ThreadDump> dumps, Map<String, Object> options) {
        StringBuilder output = new StringBuilder();
        int maxExitCode = 0;

        for (Analyzer analyzer : analyzers) {

            // Filter options to only those supported by this analyzer
            Map<String, Object> analyzerOptions = filterOptions(analyzer, options);

            // Filter dumps based on requirement
            List<ThreadDump> analyzerDumps = filterDumps(analyzer, dumps);

            // Run analyzer
            AnalyzerResult result = analyzer.analyze(analyzerDumps, analyzerOptions);

            // Append output (with section header) only if analyzer has something to display
            if (result.shouldDisplay() && !result.output().isBlank()) {
                if (!output.isEmpty()) {
                    output.append("\n");
                }
                output.append("=== ").append(analyzer.name()).append(" ===\n");
                output.append(result.output()).append("\n");
            }

            // Track max exit code
            maxExitCode = Math.max(maxExitCode, result.exitCode());
        }

        return new RunResult(output.toString().trim(), maxExitCode);
    }

    /**
     * Filters options to only those supported by the analyzer.
     */
    private Map<String, Object> filterOptions(Analyzer analyzer, Map<String, Object> options) {
        Map<String, Object> filtered = new HashMap<>();
        for (String supportedOption : analyzer.supportedOptions()) {
            if (options.containsKey(supportedOption)) {
                filtered.put(supportedOption, options.get(supportedOption));
            }
        }
        return filtered;
    }

    /**
     * Filters dumps based on analyzer requirement.
     */
    private List<ThreadDump> filterDumps(Analyzer analyzer, List<ThreadDump> dumps) {
        return switch (analyzer.dumpRequirement()) {
            case ONE -> dumps.isEmpty() ? List.of() : List.of(dumps.get(0));
            case MANY -> {
                if (dumps.size() < 2) {
                    throw new IllegalArgumentException(
                        String.format("Analyzer '%s' requires at least 2 dumps, but got %d",
                            analyzer.name(), dumps.size())
                    );
                }
                yield dumps;
            }
            case ANY -> dumps;
        };
    }

    /**
     * Result of running multiple analyzers.
     */
    public record RunResult(String output, int exitCode) {}
}