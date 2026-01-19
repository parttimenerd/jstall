package me.bechberger.jstall.provider;

import me.bechberger.jstall.model.ThreadDumpWithRaw;
import me.bechberger.jstall.util.JMXDiagnosticHelper;
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
 * <p>Thread dumps are collected using DiagnosticCommandMBean.threadPrint()
 * via JMX attachment to the target JVM process.
 */
public class JThreadDumpProvider implements ThreadDumpProvider {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    @Override
    public List<ThreadDumpWithRaw> collectFromJVM(long pid, int count, long intervalMs, Path persistTo) throws IOException {
        if (count < 1) {
            throw new IllegalArgumentException("Count must be at least 1, got: " + count);
        }

        List<ThreadDumpWithRaw> dumps = new ArrayList<>();

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
            dumps.add(new ThreadDumpWithRaw(dump, dumpContent));

            if (persistTo != null) {
                persistDump(dumpContent, persistTo, i);
            }
        }

        return dumps;
    }

    @Override
    public List<ThreadDumpWithRaw> loadFromFiles(List<Path> dumpFiles) throws IOException {
        List<ThreadDumpWithRaw> dumps = new ArrayList<>();

        for (Path file : dumpFiles) {
            String content = Files.readString(file);
            ThreadDump dump = ThreadDumpParser.parse(content);
            dumps.add(new ThreadDumpWithRaw(dump, content));
        }

        return dumps;
    }

    /**
     * Obtains a thread dump from a JVM process using DiagnosticCommandMBean.
     */
    private String obtainDumpFromJVM(long pid) throws IOException {
        try {
            // Use JMX diagnostic helper to get thread dump
            return JMXDiagnosticHelper.getThreadDump(pid);
        } catch (Exception e) {
            throw new RuntimeException("Cannot use JMX Diagnostics, maybe submit a GitHub issue at https://github.com/parttimenerd/jstall/issues if you have a reproducer", e);
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