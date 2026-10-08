package me.bechberger.jstall.cli;

import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.impl.VmVitalsAnalyzer;

import java.util.Map;

/**
 * Shows VM.vitals output (if available).
 */
@Command(
    name = "vm-vitals",
    description = "SapMachine-only: show recent JVM/process/system vitals, trends, and extremes"
)
public class VmVitalsCommand extends BaseAnalyzerCommand {

    @Option(names = "--top", description = "Number of recent VM.vitals samples to show (default: 5, -1 = all)")
    private int top = 5;

    @Override
    protected Analyzer getAnalyzer() {
        return new VmVitalsAnalyzer();
    }

    @Override
    protected Map<String, Object> getAdditionalOptions() {
        return Map.of("top", getTop(top));
    }
}
