package me.bechberger.jstall.util;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Utility for discovering running JVMs.
 */
public class JVMDiscovery {

    /**
     * Represents a running JVM process.
     */
    public record JVMProcess(long pid, String mainClass) {
        @Override
        public String toString() {
            return String.format("%5d %s", pid, mainClass);
        }
    }

    /**
     * Lists all running JVM processes
     *
     * @return List of JVM processes
     */
    public static List<JVMProcess> listJVMs() throws IOException {
        return listJVMs(null);
    }

    /**
     * Lists running JVM processes, optionally filtered by main class name.
     *
     * @param filter Optional filter string - only JVMs whose main class contains this text (case-insensitive) will be included
     * @return List of JVM processes matching the filter
     */
    public static List<JVMProcess> listJVMs(String filter) throws IOException {
        List<JVMProcess> jvms = new ArrayList<>();
        boolean hasFilter = filter != null && !filter.isBlank();
        long currentPid = ProcessHandle.current().pid();

        List<VirtualMachineDescriptor> descriptors = VirtualMachine.list();
        if (descriptors.isEmpty()) {
            return listJVMsFallback(filter);
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

            String mainClass = desc.displayName().isBlank() ? tryToFindCommandName(pid) : desc.displayName();

            if (hasFilter) {
                if (mainClass.toLowerCase().contains(filter.toLowerCase())) {
                    jvms.add(new JVMProcess(pid, mainClass));
                }
            } else {
                jvms.add(new JVMProcess(pid, mainClass));
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

    private static List<JVMProcess> listJVMsFallback(String filter) throws IOException {
        List<JVMProcess> jvms = new ArrayList<>();
        boolean hasFilter = filter != null && !filter.isBlank();
        long currentPid = ProcessHandle.current().pid();

        ProcessBuilder pb = new ProcessBuilder("jps", "-l");
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\s+", 2);
                if (parts.length < 1) {
                    continue;
                }

                long pid;
                try {
                    pid = Long.parseLong(parts[0]);
                } catch (NumberFormatException e) {
                    System.out.println("Warning: Skipping JVM with non-numeric ID: " + parts[0]);
                    continue; // Skip non-numeric IDs
                }
                // Skip current JVM and jps itself
                if (pid == currentPid || pid == process.pid()) {
                    continue;
                }
                String mainClass = parts.length > 1 && !parts[1].isEmpty() ? parts[1] : "<unknown>";
                if (hasFilter) {
                    if (mainClass.toLowerCase().contains(filter.toLowerCase())) {
                        jvms.add(new JVMProcess(pid, mainClass));
                    }
                } else {
                    jvms.add(new JVMProcess(pid, mainClass));
                }
            }
        } catch (IOException e) {
            throw new IOException("Failed to execute jps command", e);
        }

        return jvms;
    }

    /**
     * Prints available JVMs to stderr.
     */
    public static void printAvailableJVMs(PrintStream out) {
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