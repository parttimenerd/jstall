package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.impl.StatusAnalyzer;
import me.bechberger.minicli.annotations.Command;
import me.bechberger.minicli.annotations.Option;

import java.util.Map;

/**
 * The default status command - runs multiple analyzers.
 */
@Command(
    name = "status",
    description = "Run multiple analyzers over thread dumps (default command)"
)
public class StatusCommand extends BaseAnalyzerCommand {

    @Option(names = "--top", description = "Number of top threads (default: 3)")
    private int top = 3;

    @Option(names = "--no-native", description = "Ignore threads without stack traces (typically native/system threads)")
    private boolean noNative = false;

    @Override
    protected Analyzer getAnalyzer() {
        return new StatusAnalyzer();
    }

    @Override
    protected Map<String, Object> getAdditionalOptions() {
        return Map.of("top", top, "no-native", noNative);
    }
}