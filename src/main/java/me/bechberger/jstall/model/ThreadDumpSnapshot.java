package me.bechberger.jstall.model;

import me.bechberger.jthreaddump.model.ThreadDump;
import org.jetbrains.annotations.Nullable;

/**
 * Wraps a parsed ThreadDump along with its raw string representation.
 * This is necessary because some analyzers need access to the raw dump
 * content which may contain sections not preserved in the parsed model
 * (e.g., JVM-reported deadlock information).
 */
public record ThreadDumpSnapshot(ThreadDump parsed, String raw, @Nullable SystemEnvironment environment) {

    /**
     * Creates a wrapper from a parsed dump and its raw string.
     *
     * @param parsed The parsed thread dump
     * @param raw The raw thread dump string
     */
    public ThreadDumpSnapshot {
        if (parsed == null) {
            throw new IllegalArgumentException("Parsed ThreadDump cannot be null");
        }
        if (raw == null) {
            throw new IllegalArgumentException("Raw dump string cannot be null");
        }
    }

    public boolean hasEnvironment() {
        return environment != null;
    }
}