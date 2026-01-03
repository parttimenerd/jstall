package me.bechberger.jstall.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves target specifications (files, PIDs, or filters) to actual targets.
 */
public class TargetResolver {

    /**
     * Represents a resolved target.
     */
    public sealed interface ResolvedTarget permits ResolvedTarget.File, ResolvedTarget.Pid {
        record File(Path path) implements ResolvedTarget {}
        record Pid(long pid, String mainClass) implements ResolvedTarget {}
    }

    /**
     * Resolution result containing the resolved targets or an error message.
     */
    public record ResolutionResult(List<ResolvedTarget> targets, String errorMessage, boolean shouldListJVMs) {
        public boolean isSuccess() {
            return errorMessage == null && !targets.isEmpty();
        }

        public boolean isEmpty() {
            return targets.isEmpty();
        }

        public static ResolutionResult success(List<ResolvedTarget> targets) {
            return new ResolutionResult(targets, null, false);
        }

        public static ResolutionResult error(String message, boolean shouldListJVMs) {
            return new ResolutionResult(List.of(), message, shouldListJVMs);
        }
    }

    /**
     * Resolves a target specification to actual targets.
     *
     * @param target The target specification (file path, PID, or filter)
     * @return Resolution result
     */
    public static ResolutionResult resolve(String target) {
        if (target == null || target.isBlank()) {
            return ResolutionResult.error("No target specified", true);
        }

        // Check if it's an existing file first
        Path filePath = Path.of(target);
        if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
            return ResolutionResult.success(List.of(new ResolvedTarget.File(filePath)));
        }

        // Check if it's a numeric PID
        if (target.matches("\\d+")) {
            try {
                long pid = Long.parseLong(target);
                // Verify this PID exists in the JVM list
                List<JVMDiscovery.JVMProcess> jvms = JVMDiscovery.listJVMs();
                for (JVMDiscovery.JVMProcess jvm : jvms) {
                    if (jvm.pid() == pid) {
                        return ResolutionResult.success(List.of(new ResolvedTarget.Pid(pid, jvm.mainClass())));
                    }
                }
                return ResolutionResult.error("No JVM found with PID " + pid, true);
            } catch (NumberFormatException e) {
                // Fall through to filter
            } catch (IOException e) {
                return ResolutionResult.error("Failed to list JVMs: " + e.getMessage(), false);
            }
        }

        // Treat as a filter
        try {
            List<JVMDiscovery.JVMProcess> matchingJVMs = JVMDiscovery.listJVMs(target);

            if (matchingJVMs.isEmpty()) {
                return ResolutionResult.error("No JVMs found matching filter: " + target, true);
            }

            List<ResolvedTarget> targets = new ArrayList<>();
            for (JVMDiscovery.JVMProcess jvm : matchingJVMs) {
                targets.add(new ResolvedTarget.Pid(jvm.pid(), jvm.mainClass()));
            }
            return ResolutionResult.success(targets);

        } catch (IOException e) {
            return ResolutionResult.error("Failed to list JVMs: " + e.getMessage(), false);
        }
    }

    /**
     * Resolves multiple target specifications.
     *
     * @param targets List of target specifications
     * @return Resolution result
     */
    public static ResolutionResult resolveMultiple(List<String> targets) {
        if (targets == null || targets.isEmpty()) {
            return ResolutionResult.error("No targets specified", true);
        }

        List<ResolvedTarget> allTargets = new ArrayList<>();
        for (String target : targets) {
            ResolutionResult result = resolve(target);
            if (!result.isSuccess()) {
                return result; // Return first error
            }
            allTargets.addAll(result.targets());
        }

        return ResolutionResult.success(allTargets);
    }
}