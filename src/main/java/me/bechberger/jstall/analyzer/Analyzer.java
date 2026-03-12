package me.bechberger.jstall.analyzer;

import me.bechberger.jstall.provider.requirement.DataRequirements;

import java.util.Map;
import java.util.Set;

/**
 * An analyzer consumes thread dumps and produces output.
 * <p>
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
     * <p>
     * Common options: "dumps", "interval", "keep", "top"
     */
    Set<String> supportedOptions();

    /**
     * Returns the dump requirement for this analyzer.
     */
    DumpRequirement dumpRequirement();
    
    /**
     * Returns the data requirements for this analyzer.
     * <p>
     * This method declares what data needs to be collected for recording sessions.
     * The default implementation derives requirements from dumpRequirement() and options.
     * 
     * @param options Options that may affect data requirements (e.g., count, interval)
     * @return Data requirements for this analyzer
     */
    default DataRequirements getDataRequirements(Map<String, Object> options) {
        int count = getIntOption(options, "dumps", defaultDumpCount());
        long intervalMs = getLongOption(options, "interval", defaultIntervalMs());
        
        DataRequirements.Builder builder = DataRequirements.builder();
        
        // Add thread dumps based on requirement type
        if (dumpRequirement() == DumpRequirement.ONE) {
            builder.addThreadDump();
        } else if (dumpRequirement() == DumpRequirement.MANY) {
            builder.addThreadDumps(count, intervalMs);
        } else {
            // DumpRequirement.ANY - default to minimal collection
            builder.addThreadDumps(Math.max(1, count), intervalMs);
        }
        
        return builder.build();
    }
    
    private int getIntOption(Map<String, Object> options, String key, int defaultValue) {
        Object value = options.get(key);
        if (value instanceof Integer i) {
            return i;
        } else if (value instanceof Number n) {
            return n.intValue();
        }
        return defaultValue;
    }
    
    private long getLongOption(Map<String, Object> options, String key, long defaultValue) {
        Object value = options.get(key);
        if (value instanceof Long l) {
            return l;
        } else if (value instanceof Number n) {
            return n.longValue();
        }
        return defaultValue;
    }

    default int defaultDumpCount() {
        return dumpRequirement() == DumpRequirement.ONE ? 1 : 2;
    }

    default long defaultIntervalMs() {
        return 5000;
    }

    /**
     * Analyzes the provided resolved data.
     * Analyzers should implement this method directly.
     * 
     * @param data The resolved data containing thread dumps, system properties, and environment
     * @param options Analysis options
     * @return Analysis result
     */
    AnalyzerResult analyze(ResolvedData data, Map<String, Object> options);
}