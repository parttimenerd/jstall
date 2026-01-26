package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.impl.ThreadsAnalyzer;
import me.bechberger.minicli.annotations.Command;
import me.bechberger.minicli.annotations.Option;

import java.util.HashMap;
import java.util.Map;

/**
 * Lists all threads sorted by CPU time.
 */
@Command(
    name = "threads",
    description = "List all threads sorted by CPU time"
)
public class ThreadsCommand extends BaseAnalyzerCommand {

    @Option(names = "--no-native", description = "Ignore threads without stack traces (typically native/system threads)")
    private boolean noNative = false;

    @Override
    protected Analyzer getAnalyzer() {
        return new ThreadsAnalyzer();
    }

    @Override
    protected Map<String, Object> getAdditionalOptions() {
        Map<String, Object> options = new HashMap<>();
        options.put("no-native", noNative);
        return options;
    }
}