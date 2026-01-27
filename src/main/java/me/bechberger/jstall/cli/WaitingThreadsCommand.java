package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.impl.WaitingThreadsAnalyzer;
import me.bechberger.minicli.annotations.Command;
import me.bechberger.minicli.annotations.Option;

import java.util.HashMap;
import java.util.Map;

/**
 * Identifies threads that are waiting without making progress.
 */
@Command(
    name = "waiting-threads",
    description = "Identify threads waiting without progress (potentially starving)"
)
public class WaitingThreadsCommand extends BaseAnalyzerCommand {

    @Option(names = "--no-native", description = "Ignore threads without stack traces (typically native/system threads)")
    private final boolean noNative = false;

    @Option(names = "--stack-depth", description = "Stack trace depth to show (1=inline, 0=all, default: 1, in intelligent mode: max relevant frames)")
    private final int stackDepth = 1;

    @Override
    protected Analyzer getAnalyzer() {
        return new WaitingThreadsAnalyzer();
    }

    @Override
    protected Map<String, Object> getAdditionalOptions() {
        Map<String, Object> options = new HashMap<>();
        options.put("no-native", noNative);
        options.put("stack-depth", stackDepth);
        return options;
    }
}