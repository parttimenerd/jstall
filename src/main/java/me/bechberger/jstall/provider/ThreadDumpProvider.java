package me.bechberger.jstall.provider;

import me.bechberger.jstall.model.ThreadDumpSnapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Provides thread dumps from live JVMs or files.
 */
public interface ThreadDumpProvider {

    /**
     * Collects thread dumps from a live JVM. They are sorted ascending by date.
     *
     * @param pid The process ID of the JVM
     * @param count Number of dumps to collect (must be ≥ 1)
     * @param intervalMs Interval between dumps in milliseconds
     * @param persistTo Optional path to persist dumps (null = don't persist)
     * @return List of collected thread dumps with raw strings
     * @throws IOException If dump collection fails
     */
    List<ThreadDumpSnapshot> collectFromJVM(long pid, int count, long intervalMs, Path persistTo) throws IOException;

    /**
     * Loads thread dumps from files. They are sorted ascending by date.
     *
     * @param dumpFiles Paths to dump files
     * @return List of loaded thread dumps with raw strings
     * @throws IOException If loading fails
     */
    List<ThreadDumpSnapshot> loadFromFiles(List<Path> dumpFiles) throws IOException;
}