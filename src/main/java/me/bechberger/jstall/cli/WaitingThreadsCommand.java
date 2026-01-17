package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.impl.WaitingThreadsAnalyzer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.HashMap;
import java.util.Map;

/**
 * Identifies threads that are waiting without making progress.
 */
@Command(
    name = "waiting-threads",
    description = "Identify threads waiting without progress (potentially starving)",
    mixinStandardHelpOptions = true
)
public class WaitingThreadsCommand extends BaseAnalyzerCommand {

    @Option(names = "--no-native", description = "Ignore threads without stack traces (typically native/system threads)")
    private boolean noNative = false;

    @Option(names = "--stack-depth", description = "Stack trace depth to show (1=inline, 0=all, default: 1, in intelligent mode: max relevant frames)")
    private int stackDepth = 1;

    @Option(names = "--intelligent-filter", description = "Use intelligent stack trace filtering (collapses internal frames, focuses on application code)")
    private boolean intelligentFilter = false;

    @Override
    protected Analyzer getAnalyzer() {
        return new WaitingThreadsAnalyzer();
    }

    @Override
    protected Map<String, Object> getAdditionalOptions() {
        Map<String, Object> options = new HashMap<>();
        options.put("no-native", noNative);
        options.put("stack-depth", stackDepth);
        options.put("intelligent-filter", intelligentFilter);
        return options;
    }
}