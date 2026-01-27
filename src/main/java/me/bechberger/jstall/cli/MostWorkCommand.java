package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.impl.MostWorkAnalyzer;
import me.bechberger.minicli.annotations.Option;
import me.bechberger.minicli.annotations.Command;

import java.util.HashMap;
import java.util.Map;

/**
 * Identifies threads doing the most work.
 */
@Command(
    name = "most-work",
    description = "Identify threads doing the most work across dumps"
)
public class MostWorkCommand extends BaseAnalyzerCommand {

    @Option(names = "--top", description = "Number of top threads to show (default: 3)")
    private final int top = 3;

    @Option(names = "--no-native", description = "Ignore threads without stack traces (typically native/system threads)")
    private final boolean noNative = false;

    @Option(names = "--stack-depth", description = "Stack trace depth to show (default: 10, 0=all, in intelligent mode: max relevant frames)")
    private final int stackDepth = 10;

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
        return options;
    }
}