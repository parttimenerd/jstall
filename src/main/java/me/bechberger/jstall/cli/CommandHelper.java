package me.bechberger.jstall.cli;

import me.bechberger.jstall.model.ThreadDumpSnapshot;
import me.bechberger.jstall.provider.JThreadDumpProvider;
import me.bechberger.jstall.provider.ThreadDumpProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Helper class for common CLI command logic.
 */
public class CommandHelper {

    /**
     * Collects or loads thread dumps based on the provided targets.
     *
     * @param targets List of PIDs or file paths
     * @param dumpCount Number of dumps to collect (for PIDs)
     * @param intervalMs Interval between dumps in milliseconds (for PIDs)
     * @param keep Whether to persist dumps to disk
     * @return List of thread dumps with raw strings
     * @throws IOException if dump collection/loading fails
     */
    public static List<ThreadDumpSnapshot> collectDumps(
            List<String> targets,
            int dumpCount,
            long intervalMs,
            boolean keep) throws IOException {

        ThreadDumpProvider provider = new JThreadDumpProvider();
        String firstTarget = targets.get(0);

        if (firstTarget.matches("\\d+")) {
            // PID - collect dumps from running JVM
            long pid = Long.parseLong(firstTarget);
            Path persistPath = keep ? Path.of("dumps") : null;
            return provider.collectFromJVM(pid, dumpCount, intervalMs, persistPath);
        } else {
            // Files - load dumps from disk
            List<Path> files = targets.stream().map(Path::of).toList();
            return provider.loadFromFiles(files);
        }
    }
}