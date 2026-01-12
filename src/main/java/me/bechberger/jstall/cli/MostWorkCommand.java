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

    @Override
    protected Analyzer getAnalyzer() {
        return new MostWorkAnalyzer();
    }

    @Override
    protected Map<String, Object> getAdditionalOptions() {
        return Map.of("top", top, "no-native", noNative);
    }
}