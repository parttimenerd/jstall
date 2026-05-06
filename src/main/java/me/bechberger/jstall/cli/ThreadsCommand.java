package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.impl.ThreadsAnalyzer;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;

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

    @Option(names = "--top", description = "Number of top threads to show (default: -1 for all)")
    private int top = -1;

    @Option(names = "--no-native", description = "Ignore threads without stack traces (typically native/system threads)")
    boolean noNative = false;

    @Override
    protected Analyzer getAnalyzer() {
        return new ThreadsAnalyzer();
    }

    @Override
    protected Map<String, Object> getAdditionalOptions() {
        // Validate --top parameter
        if (top != -1 && top <= 0) {
            throw new IllegalArgumentException(
                "--top must be a positive integer (>= 1) or -1 to show all threads");
        }
        
        Map<String, Object> options = new HashMap<>();
        options.put("top", top);
        options.put("no-native", noNative);
        return options;
    }
}