package me.bechberger.jstall.provider.requirement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Container for data collected at a specific point in time.
 *
 * @param timestamp  Milliseconds since epoch when data was collected
 * @param rawData    Raw data as string (e.g., jcmd output, thread dump, flamegraph HTML)
 * @param metadata   Optional metadata about the collection (e.g., errors, warnings)
 * @param tempFiles  Named temporary local files (e.g., "flame", "jfr"); deleted on {@link #close()}
 */
public record CollectedData(long timestamp, String rawData, Map<String, String> metadata, Map<String, Path> tempFiles)
        implements AutoCloseable {

    /** Backward-compatible constructor without temp files. */
    public CollectedData(long timestamp, String rawData, Map<String, String> metadata) {
        this(timestamp, rawData, metadata, Map.of());
    }

    /** Creates collected data with current timestamp. */
    public static CollectedData now(String rawData) {
        return new CollectedData(System.currentTimeMillis(), rawData, Map.of());
    }

    /** Creates collected data with metadata. */
    public static CollectedData withMetadata(long timestamp, String rawData, Map<String, String> metadata) {
        return new CollectedData(timestamp, rawData, metadata);
    }

    /** Deletes all temporary files registered in this instance. */
    @Override
    public void close() {
        for (Path path : tempFiles.values()) {
            try { Files.deleteIfExists(path); } catch (IOException ignored) {}
        }
    }
}