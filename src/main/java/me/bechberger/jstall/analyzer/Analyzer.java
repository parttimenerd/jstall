package me.bechberger.jstall.analyzer;

import me.bechberger.jstall.model.ThreadDumpWithRaw;
import me.bechberger.jthreaddump.model.ThreadDump;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An analyzer consumes thread dumps and produces output.
 *
 * Analyzers are pure consumers - they do not control dump collection
 * and do not abort execution.
 */
public interface Analyzer {

    /**
     * Returns the name of this analyzer.
     */
    String name();

    /**
     * Returns the set of options this analyzer supports.
     *
     * Common options: "dumps", "interval", "keep", "top"
     */
    Set<String> supportedOptions();

    /**
     * Returns the dump requirement for this analyzer.
     */
    DumpRequirement dumpRequirement();

    /**
     * Returns the default number of dumps to collect when using this analyzer.
     * Only relevant for analyzers with dumpRequirement() == MANY.
     *
     * @return The default dump count (default: 3)
     */
    default int defaultDumpCount() {
        return dumpRequirement() == DumpRequirement.ONE ? 1 : 2;
    }

    /**
     * Returns the default interval between dumps in milliseconds.
     * Only relevant for analyzers with dumpRequirement() == MANY.
     *
     * @return The default interval in milliseconds (default: 5000)
     */
    default long defaultIntervalMs() {
        return 5000;
    }

    /**
     * Analyzes the provided thread dumps with access to raw dump strings.
     *
     * @param dumpsWithRaw The thread dumps with raw strings to analyze
     * @param options The options to use (only contains supported options)
     * @return The result of the analysis
     */
    default AnalyzerResult analyze(List<ThreadDumpWithRaw> dumpsWithRaw, Map<String, Object> options) {
        // Default implementation for backward compatibility - delegates to parsed ThreadDump only
        List<ThreadDump> dumps = dumpsWithRaw.stream().map(ThreadDumpWithRaw::parsed).toList();
        return analyzeThreadDumps(dumps, options);
    }

    /**
     * Legacy method for analyzers that don't need raw dump access.
     * Override analyze(List<ThreadDumpWithRaw>, Map) instead for full functionality.
     *
     * @param dumps The thread dumps to analyze (filtered by runner based on dumpRequirement)
     * @param options The options to use (only contains supported options)
     * @return The result of the analysis
     */
    default AnalyzerResult analyzeThreadDumps(List<ThreadDump> dumps, Map<String, Object> options) {
        throw new UnsupportedOperationException(
            "Analyzer must implement either analyze(List<ThreadDumpWithRaw>, Map) or analyzeThreadDumps(List<ThreadDump>, Map)");
    }
}