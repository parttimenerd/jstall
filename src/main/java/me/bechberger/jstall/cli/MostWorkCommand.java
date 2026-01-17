package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.impl.MostWorkAnalyzer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.HashMap;
import java.util.Map;

/**
 * Identifies threads doing the most work.
 */
@Command(
    name = "most-work",
    description = "Identify threads doing the most work across dumps",
    mixinStandardHelpOptions = true
)
public class MostWorkCommand extends BaseAnalyzerCommand {

    @Option(names = "--top", description = "Number of top threads to show (default: 3)")
    private int top = 3;

    @Option(names = "--no-native", description = "Ignore threads without stack traces (typically native/system threads)")
    private boolean noNative = false;

    @Option(names = "--stack-depth", description = "Stack trace depth to show (default: 10, 0=all, in intelligent mode: max relevant frames)")
    private int stackDepth = 10;

    @Option(names = "--intelligent-filter", description = "Use intelligent stack trace filtering (collapses internal frames, focuses on application code)")
    private boolean intelligentFilter = false;

    @Override
    protected Analyzer getAnalyzer() {
        return new MostWorkAnalyzer();
    }

    @Override
    protected Map<String, Object> getAdditionalOptions() {
        Map<String, Object> options = new HashMap<>();
        options.put("top", top);
        options.put("no-native", noNative);
        options.put("stack-depth", stackDepth);
        options.put("intelligent-filter", intelligentFilter);
        return options;
    }
}