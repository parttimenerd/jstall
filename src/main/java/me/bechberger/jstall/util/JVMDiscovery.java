package me.bechberger.jstall.util;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Utility for discovering running JVMs and resolving target specifications to actual targets.
 */
public class JVMDiscovery {

    private final CommandExecutor executor;

    public JVMDiscovery(CommandExecutor executor) {
        this.executor = executor;
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
     * Resolves a target specification (file path, PID, filter, or "all") to actual targets.
     */
    public ResolutionResult resolve(String target) {
        if (target == null || target.isBlank()) {
            return ResolutionResult.error("No target specified", true);
        }

        if (target.equalsIgnoreCase("all")) {
            try {
                List<JVMProcess> jvms = listJVMs();
                if (jvms.isEmpty()) {
                    return ResolutionResult.error("No JVMs found", true);
                }
                return ResolutionResult.success(toResolvedPidTargets(jvms));
            } catch (IOException e) {
                return ResolutionResult.error("Failed to list JVMs: " + e.getMessage(), false);
            }
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
                for (JVMProcess jvm : listJVMs()) {
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
            List<JVMProcess> matchingJVMs = listJVMs(target);
            if (matchingJVMs.isEmpty()) {
                return ResolutionResult.error("No JVMs found matching filter: " + target, true);
            }
            return ResolutionResult.success(toResolvedPidTargets(matchingJVMs));
        } catch (IOException e) {
            return ResolutionResult.error("Failed to list JVMs: " + e.getMessage(), false);
        }
    }

    /**
     * Resolves multiple target specifications.
     */
    public ResolutionResult resolveMultiple(List<String> targets) {
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

    private List<ResolvedTarget> toResolvedPidTargets(List<JVMProcess> jvms) {
        List<ResolvedTarget> targets = new ArrayList<>();
        for (JVMProcess jvm : jvms) {
            targets.add(new ResolvedTarget.Pid(jvm.pid(), jvm.mainClass()));
        }
        return targets;
    }

    // -------------------------------------------------------------------------
    // JVM discovery
    // -------------------------------------------------------------------------

    /**
     * Represents a running JVM process.
     */
    public record JVMProcess(long pid, String descriptor) {

        // Keep a compatibility accessor named mainClass() so existing callers don't need updates.
        public String mainClass() {
            return descriptor;
        }

        @Override
        public String toString() {
            if (descriptor == null || descriptor.isBlank()) {
                return String.format("%5d <unknown>", pid);
            }
            return String.format("%5d %s", pid, descriptor);
        }
    }

    /**
     * Lists all running JVM processes
     *
     * @return List of JVM processes
     */
    public List<JVMProcess> listJVMs() throws IOException {
        return listJVMs(null);
    }

    /**
     * Lists running JVM processes, optionally filtered by main class name.
     *
     *
     * @param filter Optional filter string - only JVMs whose main class contains this text (case-insensitive) will be included
     * @return List of JVM processes matching the filter
     */
    public List<JVMProcess> listJVMs(String filter) throws IOException {
        List<JVMProcess> jvms = new ArrayList<>();
        boolean hasFilter = filter != null && !filter.isBlank();

        if (executor.isRemote()) {
            // If we're executing remotely, we can't use the Attach API, so we fall back to jps
            return listJVMsFallback(filter, false);
        }

        long currentPid = ProcessHandle.current().pid();

        List<VirtualMachineDescriptor> descriptors = VirtualMachine.list();
        if (descriptors.isEmpty()) {
            return listJVMsFallback(filter, true);
        }
        for (VirtualMachineDescriptor desc : descriptors) {
            long pid;
            try {
                pid = Long.parseLong(desc.id());
            } catch (NumberFormatException e) {
                System.out.println("Warning: Skipping JVM with non-numeric ID: " + desc.id());
                continue; // Skip non-numeric IDs
            }

            // Skip current JVM
            if (pid == currentPid) {
                continue;
            }

            String mainClass = desc.displayName();
            String descriptor = !mainClass.isBlank() ? mainClass : tryToFindCommandName(pid);
            if (hasFilter) {
                if (descriptor.toLowerCase().contains(filter.toLowerCase())) {
                    jvms.add(new JVMProcess(pid, descriptor));
                }
            } else {
                jvms.add(new JVMProcess(pid, descriptor));
            }
        }

        return jvms;
    }

    private static String tryToFindCommandName(long pid) {
        try {
            ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
            if (handle != null) {
                return handle.info().command().orElse("<unknown>");
            }
        } catch (Exception e) {
            // Ignore exceptions and fall through
        }
        return "<unknown>";
    }

    private List<JVMProcess> listJVMsFallback(String filter, boolean excludeSelf) throws IOException {
        boolean hasFilter = filter != null && !filter.isBlank();
        var result = executor.executeCommand("jps", "-l");
        long currentPid = ProcessHandle.current().pid();
        return result.out().lines().map(
            line -> line.split("\\s+", 2)
        ).filter(parts -> parts.length >= 1).map(parts -> {
            long pid;
            try {
                pid = Long.parseLong(parts[0]);
            } catch (NumberFormatException e) {
                System.out.println("Warning: Skipping JVM with non-numeric ID: " + parts[0]);
                return null; // Skip non-numeric IDs
            }
            // Skip current JVM and jps itself
            if (excludeSelf && (pid == currentPid || pid == result.pid())) {
                return null;
            }
            String command = parts.length > 1 && !parts[1].isEmpty() ? parts[1] : "<unknown>";
            String descriptor = command == null || command.isBlank() ? "" : command;
            
            // Apply filter if specified
            if (hasFilter && !descriptor.toLowerCase().contains(filter.toLowerCase())) {
                return null;  // Filter doesn't match, skip this JVM
            }
            return new JVMProcess(pid, descriptor);
        }).filter(Objects::nonNull).toList();
    }

    /**
     * Prints available JVMs to stderr.
     */
    public void printAvailableJVMs(PrintStream out) {
        try {
            List<JVMProcess> jvms = listJVMs();

            if (jvms.isEmpty()) {
                out.println("No running JVMs found");
            } else {
                out.println("Available JVMs:");
                for (JVMProcess jvm : jvms) {
                    out.println("  " + jvm);
                }
            }
        } catch (IOException e) {
            out.println("Failed to list JVMs: " + e.getMessage());
        }
    }
}