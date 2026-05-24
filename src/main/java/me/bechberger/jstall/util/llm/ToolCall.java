package me.bechberger.jstall.util.llm;

import java.util.Map;

/**
 * Represents a tool call emitted by the LLM.
 *
 * @param id       The tool call ID (used to correlate the response)
 * @param name     The function name
 * @param arguments Parsed arguments as a map
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {

    /**
     * Gets a string argument, returning defaultValue if missing.
     */
    public String getString(String key, String defaultValue) {
        Object v = arguments.get(key);
        return v instanceof String s ? s : defaultValue;
    }

    /**
     * Gets a string argument, returning null if missing.
     */
    public String getString(String key) {
        return getString(key, null);
    }

    /**
     * Gets an integer argument, returning defaultValue if missing.
     */
    public int getInt(String key, int defaultValue) {
        Object v = arguments.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
