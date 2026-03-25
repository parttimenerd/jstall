package me.bechberger.jstall.cli;

import me.bechberger.jstall.model.ThreadDumpSnapshot;
import me.bechberger.jstall.provider.requirement.ThreadDumpRequirement;
import me.bechberger.jstall.util.CommandExecutor;

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
     * @param executor Command executor for obtaining diagnostic helpers
     * @param targets List of PIDs or file paths
     * @param dumpCount Number of dumps to collect (for PIDs)
     * @param intervalMs Interval between dumps in milliseconds (for PIDs)
     * @param keep Whether to persist dumps to disk
     * @return List of thread dumps with raw strings
     * @throws IOException if dump collection/loading fails
     */
    public static List<ThreadDumpSnapshot> collectDumps(
            CommandExecutor executor,
            List<String> targets,
            int dumpCount,
            long intervalMs,
            boolean keep) throws IOException {

        String firstTarget = targets.get(0);
        if (firstTarget.matches("\\d+")) {
            long pid = Long.parseLong(firstTarget);
            // Thread dumps are now collected via DataCollector; callers should use DataCollector directly.
            // This helper is kept for backward compat — collect via JcmdRequirement approach.
            var req = new ThreadDumpRequirement(
                    me.bechberger.jstall.provider.requirement.CollectionSchedule.intervals(dumpCount, intervalMs));
            var helper = executor.diagnosticHelper(pid);
            var collector = new me.bechberger.jstall.provider.DataCollector(helper,
                    me.bechberger.jstall.provider.requirement.DataRequirements.builder()
                            .addThreadDumps(dumpCount, intervalMs).build());
            var collected = collector.collectAll();
            var dumpData = collected.entrySet().stream()
                    .filter(e -> ThreadDumpRequirement.TYPE.equals(e.getKey().getType()))
                    .flatMap(e -> e.getValue().stream()).toList();
            if (keep && !dumpData.isEmpty()) {
                ThreadDumpRequirement.persistToDirectory(dumpData, Path.of("dumps"));
            }
            return ThreadDumpRequirement.toSnapshots(dumpData, null,
                    me.bechberger.jstall.model.SystemEnvironment.create(executor));
        } else {
            // Files - load dumps from disk
            List<Path> files = targets.stream().map(Path::of).toList();
            return ThreadDumpRequirement.loadFromFiles(files);
        }
    }
}