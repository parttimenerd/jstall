package me.bechberger.jstall.util;

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
            return pid + " " + mainClass;
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

        try {
            ProcessBuilder pb = new ProcessBuilder("jps", "-l");
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }

                    // Format: "PID MainClass"
                    String[] parts = line.split("\\s+", 2);
                    if (parts.length >= 1) {
                        try {
                            long pid = Long.parseLong(parts[0]);
                            String mainClass = parts.length > 1 ? parts[1] : "<unknown>";

                            // Skip jps itself and the current JVM (jstall)
                            if (mainClass.contains("jps") || pid == currentPid) {
                                continue;
                            }

                            // Apply filter if provided
                            if (hasFilter && !mainClass.toLowerCase().contains(filter.toLowerCase())) {
                                continue;
                            }

                            jvms.add(new JVMProcess(pid, mainClass));
                        } catch (NumberFormatException e) {
                            // Skip invalid lines
                        }
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("jps failed with exit code " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while listing JVMs", e);
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