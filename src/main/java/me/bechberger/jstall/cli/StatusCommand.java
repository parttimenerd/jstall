package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.impl.StatusAnalyzer;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;

import java.util.Map;

/**
 * The default status command - runs multiple analyzers.
 */
@Command(
    name = "status",
    description = "Best first check: summarize JVM health, hot threads, memory, deadlocks, and lock contention"
)
public class StatusCommand extends BaseAnalyzerCommand {

    @Option(names = "--top", description = "How many hottest threads to show in status tables (default: 3, -1 = all)")
    private int top = 3;

    @Option(names = "--no-native", description = "Hide threads without Java stack traces (typically native/system threads)")
    private boolean noNative = false;

    @Override
    protected Analyzer getAnalyzer() {
        return new StatusAnalyzer();
    }

    @Override
    protected Map<String, Object> getAdditionalOptions() {
        return Map.of("top", getTop(top), "no-native", noNative);
    }
}