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
    description = "Show VM.vitals (if available)"
)
public class VmVitalsCommand extends BaseAnalyzerCommand {

    @Option(names = "--top", description = "Number of VM.vitals rows to show (default: 5)")
    private int top = 5;

    @Override
    protected Analyzer getAnalyzer() {
        return new VmVitalsAnalyzer();
    }

    @Override
    protected Map<String, Object> getAdditionalOptions() {
        // Validate --top parameter
        if (top != -1 && top <= 0) {
            throw new IllegalArgumentException(
                "--top must be a positive integer (>= 1) or -1 to show all rows");
        }
        return Map.of("top", top);
    }
}
