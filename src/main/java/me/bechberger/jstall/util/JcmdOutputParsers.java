package me.bechberger.jstall.util;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Utility parsers for outputs of {@code jcmd} diagnostic commands. */
public final class JcmdOutputParsers {

    private JcmdOutputParsers() {
    }

    /**
     * Parse {@code jcmd <pid> VM.system_properties} output as a map.
     * <p>
     * Ignores the leading {@code <pid>:} line (if present) and the timestamp line starting with {@code #}
     * (e.g. {@code #Mon Feb 09 12:38:52 CET 2026}).
     * <p>
     * Parses {@code key=value} pairs; empty values are allowed. Lines without '=' are ignored.
     */
    public static Map<String, String> parseVmSystemProperties(@Nullable String output) {
        if (output == null || output.isBlank()) {
            return Collections.emptyMap();
        }

        Map<String, String> props = new LinkedHashMap<>();
        String[] lines = output.split("\\R");
        for (String rawLine : lines) {
            if (rawLine == null) {
                continue;
            }
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            // Example: "17432:" (pid header)
            if (line.endsWith(":") && line.substring(0, line.length() - 1).chars().allMatch(Character::isDigit)) {
                continue;
            }
            // Example: "#Mon Feb 09 12:38:52 CET 2026"
            if (line.startsWith("#")) {
                continue;
            }

            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (!key.isEmpty()) {
                props.put(key, value);
            }
        }
        return props;
    }
}