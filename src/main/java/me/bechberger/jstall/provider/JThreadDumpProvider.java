package me.bechberger.jstall.provider;

import me.bechberger.jthreaddump.model.ThreadDump;
import me.bechberger.jthreaddump.parser.ThreadDumpParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of ThreadDumpProvider using jthreaddump library.
 *
 * <p>Thread dumps are collected using jstack (preferred) or jcmd (fallback).
 * jstack is preferred as it provides better output quality and more detailed information.
 */
public class JThreadDumpProvider implements ThreadDumpProvider {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    @Override
    public List<ThreadDump> collectFromJVM(long pid, int count, long intervalMs, Path persistTo) throws IOException {
        if (count < 1) {
            throw new IllegalArgumentException("Count must be at least 1, got: " + count);
        }

        List<ThreadDump> dumps = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            if (i > 0) {
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting between dumps", e);
                }
            }

            String dumpContent = obtainDumpFromJVM(pid);
            ThreadDump dump = ThreadDumpParser.parse(dumpContent);
            dumps.add(dump);

            if (persistTo != null) {
                persistDump(dumpContent, persistTo, i);
            }
        }

        return dumps;
    }

    @Override
    public List<ThreadDump> loadFromFiles(List<Path> dumpFiles) throws IOException {
        List<ThreadDump> dumps = new ArrayList<>();

        for (Path file : dumpFiles) {
            String content = Files.readString(file);
            ThreadDump dump = ThreadDumpParser.parse(content);
            dumps.add(dump);
        }

        return dumps;
    }

    /**
     * Obtains a thread dump from a JVM process.
     * Tries jstack first (if available), then falls back to jcmd.
     */
    private String obtainDumpFromJVM(long pid) throws IOException {
        // Try jstack first (preferred for better output quality)
        try {
            return obtainDumpWithJstack(pid);
        } catch (IOException e) {
            // jstack not available or failed, try jcmd
            try {
                return obtainDumpWithJcmd(pid);
            } catch (IOException jcmdException) {
                // Both failed, throw combined error
                throw new IOException(
                    "Failed to obtain thread dump with both jstack and jcmd. " +
                    "jstack error: " + e.getMessage() + ", " +
                    "jcmd error: " + jcmdException.getMessage(),
                    jcmdException
                );
            }
        }
    }

    /**
     * Obtains a thread dump using jstack.
     */
    private String obtainDumpWithJstack(long pid) throws IOException {
        try {
            ProcessBuilder pb = new ProcessBuilder("jstack", String.valueOf(pid));
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            String error = new String(process.getErrorStream().readAllBytes());
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new IOException("jstack failed with exit code " + exitCode +
                    (error.isEmpty() ? "" : ": " + error));
            }

            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while collecting dump with jstack", e);
        } catch (IOException e) {
            // jstack not found or failed
            throw new IOException("jstack not available or failed: " + e.getMessage(), e);
        }
    }

    /**
     * Obtains a thread dump using jcmd.
     */
    private String obtainDumpWithJcmd(long pid) throws IOException {
        try {
            ProcessBuilder pb = new ProcessBuilder("jcmd", String.valueOf(pid), "Thread.print");
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            String error = new String(process.getErrorStream().readAllBytes());
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new IOException("jcmd failed with exit code " + exitCode +
                    (error.isEmpty() ? "" : ": " + error));
            }

            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while collecting dump with jcmd", e);
        }
    }

    /**
     * Persists a dump to disk.
     */
    private void persistDump(String dumpContent, Path baseDir, int index) throws IOException {
        Files.createDirectories(baseDir);

        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String filename = String.format("threaddump-%s-%03d.txt", timestamp, index);
        Path dumpFile = baseDir.resolve(filename);

        Files.writeString(dumpFile, dumpContent);
    }
}