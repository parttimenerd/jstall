package me.bechberger.jstall.util;

import java.nio.file.Path;

/**
 * Represents a resolved target.
 */
public sealed interface ResolvedTarget permits ResolvedTarget.File, ResolvedTarget.Pid {
    record File(Path path) implements ResolvedTarget {
    }

    record Pid(long pid, String mainClass) implements ResolvedTarget {
    }
}