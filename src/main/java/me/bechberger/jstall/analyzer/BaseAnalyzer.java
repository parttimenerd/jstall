package me.bechberger.jstall.analyzer;

import java.util.Map;

/**
 * Base class for analyzers providing common option handling utilities.
 *
 * Default values for options should be provided by the command classes (e.g., BaseAnalyzerCommand),
 * but analyzers can also handle missing values gracefully for testing purposes.
 */
public abstract class BaseAnalyzer implements Analyzer {

    /**
     * Retrieves a boolean option from the options map with a default value.
     *
     * @param options The options map
     * @param key The option key
     * @param defaultValue The default value if key is missing
     * @return The option value or default
     */
    protected boolean getBooleanOption(Map<String, Object> options, String key, boolean defaultValue) {
        Object value = options.get(key);
        return value instanceof Boolean ? (Boolean) value : defaultValue;
    }

    /**
     * Retrieves an integer option from the options map with a default value.
     *
     * @param options The options map
     * @param key The option key
     * @param defaultValue The default value if key is missing
     * @return The option value or default
     */
    protected int getIntOption(Map<String, Object> options, String key, int defaultValue) {
        Object value = options.get(key);
        return value instanceof Integer ? (Integer) value : defaultValue;
    }

    /**
     * Retrieves a string option from the options map with a default value.
     *
     * @param options The options map
     * @param key The option key
     * @param defaultValue The default value if key is missing
     * @return The option value or default
     */
    protected String getStringOption(Map<String, Object> options, String key, String defaultValue) {
        Object value = options.get(key);
        return value instanceof String ? (String) value : defaultValue;
    }

    /**
     * Checks if JSON output is requested.
     *
     * @param options The options map
     * @return true if JSON output is requested, false otherwise
     */
    protected boolean isJsonOutput(Map<String, Object> options) {
        return getBooleanOption(options, "json", false);
    }
}