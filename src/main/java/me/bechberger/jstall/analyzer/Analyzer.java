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

    default int defaultDumpCount() {
        return dumpRequirement() == DumpRequirement.ONE ? 1 : 2;
    }

    default long defaultIntervalMs() {
        return 5000;
    }

    default AnalyzerResult analyze(List<ThreadDumpWithRaw> dumpsWithRaw, Map<String, Object> options) {
        List<ThreadDump> dumps = dumpsWithRaw.stream().map(ThreadDumpWithRaw::parsed).toList();
        return analyzeThreadDumps(dumps, options);
    }

    default AnalyzerResult analyzeThreadDumps(List<ThreadDump> dumps, Map<String, Object> options) {
        throw new UnsupportedOperationException(
            "Analyzer must implement either analyze(List<ThreadDumpWithRaw>, Map) or analyzeThreadDumps(List<ThreadDump>, Map)");
    }
}