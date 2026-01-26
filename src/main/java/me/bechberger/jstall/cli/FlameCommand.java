package me.bechberger.jstall.cli;

import me.bechberger.minicli.annotations.Command;
import me.bechberger.minicli.MiniCli;
import me.bechberger.minicli.annotations.Option;
import me.bechberger.jstall.util.JVMDiscovery;
import me.bechberger.jstall.util.TargetResolver;
import me.bechberger.minicli.annotations.Parameters;
import one.profiler.AsyncProfilerLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Generates a flamegraph using async-profiler via ap-loader.
 */
@Command(
    name = "flame",
    description = "Generate a flamegraph of the application using async-profiler"
)
public class FlameCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "PID or filter (filters JVMs by main class name)")
    String target;

    @Option(names = {"-o", "--output"}, description = "Output HTML file (default: flame.html)")
    private String outputFile = "flame.html";

    @Option(names = {"-d", "--duration"}, description = "Profiling duration (default: 10s)")
    String duration = "10s";

    @Option(names = {"-e", "--event"}, description = "Profiling event (default: cpu). Options: cpu, alloc, lock, wall, itimer")
    private String event = "cpu";

    @Option(names = {"-i", "--interval"}, description = "Sampling interval (default: 10ms)")
    private String interval = "10ms";

    @Option(names = {"--open"}, description = "Automatically open the generated HTML file in browser")
    private boolean open = false;

    @Override
    public Integer call() {
        // Show help and list JVMs if no target specified
        if (target == null) {
            MiniCli.usage(this, System.out);
            System.out.println();
            JVMDiscovery.printAvailableJVMs(System.out);
            return 1;
        }

        // Resolve target to PID
        TargetResolver.ResolutionResult resolution = TargetResolver.resolve(target);

        if (!resolution.isSuccess()) {
            System.err.println("Error: " + resolution.errorMessage());
            if (resolution.shouldListJVMs()) {
                System.err.println();
                JVMDiscovery.printAvailableJVMs(System.err);
            }
            return 1;
        }

        // Flame command only supports single target
        if (resolution.targets().size() > 1) {
            System.err.println("Error: flame command does not support multiple targets");
            System.err.println("Filter matched " + resolution.targets().size() + " JVMs:");
            for (TargetResolver.ResolvedTarget resolvedTarget : resolution.targets()) {
                if (resolvedTarget instanceof TargetResolver.ResolvedTarget.Pid(long pid, String mainClass)) {
                    System.err.println("  PID " + pid + ": " + mainClass);
                }
            }
            System.err.println("\nPlease specify a more specific filter or use an exact PID.");
            return 1;
        }

        TargetResolver.ResolvedTarget resolvedTarget = resolution.targets().getFirst();

        // Flame command only works with PIDs, not files
        if (!(resolvedTarget instanceof TargetResolver.ResolvedTarget.Pid(long pid, String mainClass))) {
            System.err.println("Error: flame command only works with running JVMs, not thread dump files");
            return 1;
        }

        System.out.println("Starting flamegraph generation for PID " + pid + " (" + mainClass + ")...");
        System.out.println("Event: " + event);
        System.out.println("Duration: " + duration);
        System.out.println("Interval: " + interval);
        System.out.println("Output: " + outputFile);
        System.out.println();

        // Check if async-profiler is supported on this platform
        if (!AsyncProfilerLoader.isSupported()) {
            System.err.println("Error: async-profiler is not supported on this OS and architecture.");
            System.err.println("Supported platforms: Linux (x64, arm64), macOS");
            return 1;
        }

        try {
            // Parse duration and interval
            long durationSeconds = parseDurationToSeconds(duration);
            long intervalNanos = parseIntervalToNanos(interval);

            // Determine format from file extension
            String format = determineFormat(outputFile);

            // Build profiler command arguments
            List<String> args = new ArrayList<>();

            // Start profiling
            args.add("-d");
            args.add(String.valueOf(durationSeconds));
            args.add("-e");
            args.add(event);
            args.add("-i");
            args.add(String.valueOf(intervalNanos));
            args.add("-f");
            args.add(outputFile);
            args.add(String.valueOf(pid));

            System.out.println("Executing: asprof " + String.join(" ", args));
            System.out.println();

            // Execute via AsyncProfilerLoader
            AsyncProfilerLoader.ExecutionResult result = AsyncProfilerLoader.executeProfiler(args.toArray(new String[0]));

            System.out.println(result.getStdout());
            if (!result.getStderr().isEmpty()) {
                System.err.println(result.getStderr());
            }

            // Check if output file was created
            Path outputPath = Paths.get(outputFile).toAbsolutePath();
            if (Files.exists(outputPath)) {
                System.out.println("\n✓ Flamegraph successfully generated!");
                System.out.println("Output file: " + outputPath);
                System.out.println("File size: " + Files.size(outputPath) + " bytes");

                if (open) {
                    // Automatically open the file in browser
                    if (openInBrowser(outputPath)) {
                        System.out.println("\nOpened flamegraph in browser.");
                    } else {
                        System.out.println("\nCould not automatically open browser. Open manually with:");
                        System.out.println("  open " + outputPath + " (macOS)");
                        System.out.println("  xdg-open " + outputPath + " (Linux)");
                    }
                }
            } else {
                System.err.println("Warning: Output file not found: " + outputPath);
                return 1;
            }

            return 0;

        } catch (IllegalArgumentException e) {
            System.err.println("Error parsing duration or interval: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            System.err.println("Error executing async-profiler: " + e.getMessage());
            e.printStackTrace();
            return 1;
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    /**
     * Parses a duration string to seconds.
     * Supports: 30s, 2m, 500ms, 60 (bare numbers treated as seconds)
     */
    private long parseDurationToSeconds(String duration) {
        long millis = CommandHelper.parseDuration(duration);
        return millis / 1000;
    }

    /**
     * Determines the output format from the file extension.
     * @param filename The output filename
     * @return The format string for async-profiler (html, jfr, collapsed, etc.)
     */
    private String determineFormat(String filename) {
        if (filename.endsWith(".jfr")) {
            return "jfr";
        } else if (filename.endsWith(".collapsed") || filename.endsWith(".txt")) {
            return "collapsed";
        } else {
            // Default to HTML for .html or any other extension
            return "html";
        }
    }

    /**
     * Parses an interval string to nanoseconds.
     * Supports: 10ms, 1s, 1000000ns, 1000 (bare numbers treated as nanoseconds)
     */
    private long parseIntervalToNanos(String interval) {
        if (interval.endsWith("ns")) {
            return Long.parseLong(interval.substring(0, interval.length() - 2));
        } else if (interval.endsWith("us") || interval.endsWith("μs")) {
            return Long.parseLong(interval.substring(0, interval.length() - 2)) * 1_000L;
        } else if (interval.endsWith("ms")) {
            return Long.parseLong(interval.substring(0, interval.length() - 2)) * 1_000_000L;
        } else if (interval.endsWith("s")) {
            return Long.parseLong(interval.substring(0, interval.length() - 1)) * 1_000_000_000L;
        } else {
            // Assume nanoseconds for bare numbers
            return Long.parseLong(interval);
        }
    }

    /**
     * Opens the given file in the default browser.
     * @param filePath Path to the file to open
     * @return true if the file was successfully opened, false otherwise
     */
    private boolean openInBrowser(Path filePath) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;

            if (os.contains("mac") || os.contains("darwin")) {
                // macOS
                pb = new ProcessBuilder("open", filePath.toString());
            } else if (os.contains("nux") || os.contains("nix")) {
                // Linux
                pb = new ProcessBuilder("xdg-open", filePath.toString());
            } else {
                return false;
            }

            pb.start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}