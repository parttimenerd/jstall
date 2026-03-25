package me.bechberger.jstall.provider.requirement;

import me.bechberger.jstall.model.SystemEnvironment;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
import me.bechberger.jstall.util.CommandExecutor;
import me.bechberger.jthreaddump.parser.ThreadDumpParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Requirement for collecting JVM thread dumps via {@code Thread.print}.
 * <p>
 * Extends {@link JcmdRequirement} with utilities for converting raw
 * {@link CollectedData} into typed {@link ThreadDumpSnapshot} objects
 * and for loading dumps from plain text files.
 */
public class ThreadDumpRequirement extends JcmdRequirement {

    /** Key used in {@code collectedDataByType} maps. */
    public static final String TYPE = "thread-dumps";

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    public ThreadDumpRequirement(CollectionSchedule schedule) {
        super("Thread.print", null, schedule);
    }

    // -------------------------------------------------------------------------
    // Static utilities
    // -------------------------------------------------------------------------

    /**
     * Converts raw {@link CollectedData} samples to {@link ThreadDumpSnapshot} objects.
     *
     * @param data        Collected thread dump samples (raw jcmd Thread.print output)
     * @param systemProps Optional system properties to embed in each snapshot
     * @param env         Optional system environment to embed in each snapshot
     */
    public static List<ThreadDumpSnapshot> toSnapshots(List<CollectedData> data,
                                                       Map<String, String> systemProps,
                                                       SystemEnvironment env) {
        return data.stream()
                .filter(d -> !d.rawData().isBlank())
                .map(d -> {
                    try {
                        return new ThreadDumpSnapshot(
                                ThreadDumpParser.parse(d.rawData()), d.rawData(), env, systemProps);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to parse thread dump", e);
                    }
                })
                .toList();
    }

    /**
     * Loads and parses thread dumps from plain text files, sorted by timestamp.
     */
    public static List<ThreadDumpSnapshot> loadFromFiles(List<Path> files) throws IOException {
        List<ThreadDumpSnapshot> dumps = new ArrayList<>();
        for (Path file : files) {
            String content = Files.readString(file);
            dumps.add(new ThreadDumpSnapshot(ThreadDumpParser.parse(content), content, null, null));
        }
        dumps.sort(Comparator.comparing(d -> d.parsed().timestamp()));
        return dumps;
    }

    /**
     * Persists thread dump {@link CollectedData} as individual {@code .txt} files under {@code directory}.
     */
    public static void persistToDirectory(List<CollectedData> data, Path directory) throws IOException {
        Files.createDirectories(directory);
        for (int i = 0; i < data.size(); i++) {
            String ts = LocalDateTime.now().format(TIMESTAMP_FORMAT);
            Path file = directory.resolve(String.format("threaddump-%s-%03d.txt", ts, i));
            Files.writeString(file, data.get(i).rawData());
        }
    }
}