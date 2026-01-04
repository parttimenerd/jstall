package me.bechberger.jstall.util;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;
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
     * Lists all running JVM processes using jps.
     *
     * @return List of JVM processes
     * @throws IOException if jps fails
     */
    public static List<JVMProcess> listJVMs() throws IOException {
        return listJVMs(null);
    }

    /**
     * Lists running JVM processes using jps, optionally filtered by main class name.
     *
     * @param filter Optional filter string - only JVMs whose main class contains this text (case-insensitive) will be included
     * @return List of JVM processes matching the filter
     * @throws IOException if jps fails
     */
    public static List<JVMProcess> listJVMs(String filter) throws IOException {
        List<JVMProcess> jvms = new ArrayList<>();
        boolean hasFilter = filter != null && !filter.isBlank();
        long currentPid = ProcessHandle.current().pid();

        List<VirtualMachineDescriptor> descriptors = VirtualMachine.list();

        for (VirtualMachineDescriptor desc : descriptors) {
            long pid;
            try {
                pid = Long.parseLong(desc.id());
            } catch (NumberFormatException e) {
                continue; // Skip non-numeric IDs
            }

            // Skip current JVM
            if (pid == currentPid) {
                continue;
            }

            String mainClass = desc.displayName().isBlank() ? "<unknown>" : desc.displayName();

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