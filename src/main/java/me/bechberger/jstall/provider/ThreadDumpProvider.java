package me.bechberger.jstall.provider;

import me.bechberger.jstall.model.ThreadDumpWithRaw;
import me.bechberger.jthreaddump.model.ThreadDump;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Provides thread dumps from live JVMs or files.
 */
public interface ThreadDumpProvider {

    /**
     * Collects thread dumps from a live JVM.
     *
     * @param pid The process ID of the JVM
     * @param count Number of dumps to collect (must be ≥ 1)
     * @param intervalMs Interval between dumps in milliseconds
     * @param persistTo Optional path to persist dumps (null = don't persist)
     * @return List of collected thread dumps with raw strings
     * @throws IOException If dump collection fails
     */
    List<ThreadDumpWithRaw> collectFromJVM(long pid, int count, long intervalMs, Path persistTo) throws IOException;

    /**
     * Loads thread dumps from files.
     *
     * @param dumpFiles Paths to dump files
     * @return List of loaded thread dumps with raw strings
     * @throws IOException If loading fails
     */
    List<ThreadDumpWithRaw> loadFromFiles(List<Path> dumpFiles) throws IOException;
}