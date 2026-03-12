package me.bechberger.jstall.provider.requirement;

import me.bechberger.jstall.util.JMXDiagnosticHelper;

import java.io.IOException;
import java.util.List;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Defines a type of data that can be collected from a JVM and persisted/loaded.
 * <p>
 * This interface enables a plugin-style architecture where new data types
 * (thread dumps, heap info, JFR recordings, etc.) can be added without
 * modifying the recording/replay framework.
 */
public interface DataRequirement {
    
    /**
     * Unique identifier for this data type (e.g., "thread-dump", "system-properties").
     * Used for organizing data in recordings and for matching requirements.
     */
    String getType();
    
    /**
     * Returns the collection schedule for this requirement.
     */
    CollectionSchedule getSchedule();
    
    /**
     * Collects one sample of data from the target JVM.
     *
     * @param helper JMX diagnostic helper connected to target JVM
     * @param sampleIndex Zero-based index of this sample (0 for first, 1 for second, etc.)
     * @return Collected data with timestamp
     * @throws IOException if collection fails
     */
    CollectedData collect(JMXDiagnosticHelper helper, int sampleIndex) throws IOException;
    
    /**
     * Persists all collected samples to a zip file.
     *
     * @param zipOut Zip output stream
     * @param pidPath Base path within zip for this PID (e.g., "12345/")
     * @param samples All samples collected for this requirement
     * @throws IOException if writing fails
     */
    void persist(ZipOutputStream zipOut, String pidPath, List<CollectedData> samples) throws IOException;
    
    /**
     * Loads previously persisted data from a zip file.
     *
     * @param zipFile Zip file containing recording
     * @param pidPath Base path within zip for this PID (e.g., "12345/")
     * @return List of collected data in chronological order
     * @throws IOException if reading fails
     */
    List<CollectedData> load(ZipFile zipFile, String pidPath) throws IOException;
    
    /**
     * Returns a human-readable description of this requirement.
     */
    default String getDescription() {
        CollectionSchedule schedule = getSchedule();
        if (schedule.isMultiple()) {
            return String.format("%s (%d samples @ %dms intervals)", 
                getType(), schedule.count(), schedule.intervalMs());
        }
        return getType() + " (once)";
    }

    /**
     * Returns a human-readable description of the directory this requirement writes to.
     * Used by the README generator to describe the archive structure.
     *
     * @return description string, or {@code null} if this requirement should not appear in the archive structure docs
     */
    default String getDirectoryDescription() {
        return null;
    }

    /**
     * Returns the list of relative file paths (relative to the PID folder, e.g. "thread-dumps/000-123.txt")
     * that {@link #persist} would write for the given samples.
     *
     * <p>This keeps file-naming logic co-located with {@code persist()} so the README
     * generator can list actual files without duplicating naming conventions.</p>
     *
     * @param samples the collected samples (same list that would be passed to {@code persist})
     * @return list of relative paths; empty list if no files would be written
     */
    default List<String> getExpectedFiles(List<CollectedData> samples) {
        return List.of();
    }
}