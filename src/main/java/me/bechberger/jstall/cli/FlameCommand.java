package me.bechberger.jstall.cli;

import me.bechberger.femtocli.Spec;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.jstall.Main;
import me.bechberger.jstall.provider.ReplayProvider;
import me.bechberger.jstall.provider.requirement.AsyncProfilerWindowRequirement;
import me.bechberger.jstall.provider.requirement.CollectionSchedule;
import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jstall.util.CommandExecutor;
import me.bechberger.jstall.util.JMXDiagnosticHelper;
import me.bechberger.jstall.util.JVMDiscovery;
import me.bechberger.femtocli.annotations.Parameters;
import me.bechberger.jstall.util.ResolvedTarget;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Generates a flamegraph using async-profiler via ap-loader.
 */
@Command(
    name = "flame",
    description = "Generate a flamegraph of the application using async-profiler",
    footer = """
            Examples:
              jstall flame 12345 --output flame.html --duration 15s
              # Allocation flamegraph for a JVM running MyAppMainClass with a 20s duration
              # open flamegraph automatically after generation
              jstall flame MyAppMainClass --event alloc --duration 20s --open
            """
)
public class FlameCommand implements Callable<Integer> {

    @Parameters(arity = "0..1", description = "PID or filter (filters JVMs by main class name)")
    String target;

    @Option(names = {"-o", "--output"}, description = "Output HTML file (default: flame.html)")
    private String outputFile = "flame.html";

    @Option(names = {"-d", "--duration"}, defaultValue = "10s", description = "Profiling duration (default: 10s)")
    Duration duration;

    @Option(names = {"-e", "--event"}, description = "Profiling event (default: cpu). Options: cpu, alloc, lock, wall, itimer")
    private String event = "cpu";

    @Option(names = {"-i", "--interval"}, defaultValue = "10ms", description = "Sampling interval (default: 10ms)")
    private Duration interval;

    @Option(names = {"--open"}, description = "Automatically open the generated HTML file in browser")
    private boolean open = false;

    Spec spec;

    @Override
    public Integer call() {
        var replayFile = spec.getParent(Main.class).getReplayFile();
        if (replayFile != null) return useReplayFile(replayFile);

        var executor = spec.getParent(Main.class).executor();
        var jvmDiscovery = new JVMDiscovery(executor);
        System.err.println("Interval set to: " + interval.toMillis() + " ms");

        if (target == null) {
            spec.usage();
            System.out.println();
            jvmDiscovery.printAvailableJVMs(System.out);
            return 1;
        }

        ResolvedTarget.Pid pid = resolveSinglePid(jvmDiscovery);
        if (pid == null) return 1;

        return profilePid(pid, executor);
    }

    /** Resolves {@code target} to a single live PID, printing errors and returning null on failure. */
    private ResolvedTarget.Pid resolveSinglePid(JVMDiscovery jvmDiscovery) {
        JVMDiscovery.ResolutionResult resolution = jvmDiscovery.resolve(target);
        if (!resolution.isSuccess()) {
            System.err.println("Error: " + resolution.errorMessage());
            if (resolution.shouldListJVMs()) {
                System.err.println();
                jvmDiscovery.printAvailableJVMs(System.err);
            }
            return null;
        }
        if (resolution.targets().size() > 1) {
            System.err.println("Error: flame command does not support multiple targets");
            System.err.println("Filter matched " + resolution.targets().size() + " JVMs:");
            resolution.targets().stream()
                .filter(t -> t instanceof ResolvedTarget.Pid)
                .map(t -> (ResolvedTarget.Pid) t)
                .forEach(p -> System.err.println("  PID " + p.pid() + ": " + p.mainClass()));
            System.err.println("\nPlease specify a more specific filter or use an exact PID.");
            return null;
        }
        ResolvedTarget resolvedTarget = resolution.targets().get(0);
        if (!(resolvedTarget instanceof ResolvedTarget.Pid pid)) {
            System.err.println("Error: flame command only works with running JVMs, not thread dump files");
            return null;
        }
        return pid;
    }

    /** Runs async-profiler against the given PID and writes the result to {@code outputFile}. */
    private int profilePid(ResolvedTarget.Pid pid, CommandExecutor executor) {
        System.out.println("Starting flamegraph generation for PID " + pid.pid() + " (" + pid.mainClass() + ")...");
        System.out.println("Event: " + event + "  Duration: " + duration + "  Interval: " + interval + "  Output: " + outputFile);
        System.out.println();

        if (!executor.isRemote() && !AsyncProfilerWindowRequirement.isPlatformSupported()) {
            System.err.println("Error: async-profiler is not supported on this OS and architecture.");
            System.err.println("Supported platforms: Linux (x64, arm64), macOS");
            return 1;
        }

        try {
            AsyncProfilerWindowRequirement req = new AsyncProfilerWindowRequirement(
                    CollectionSchedule.once(), event, false, interval.toNanos());
            JMXDiagnosticHelper helper = executor.diagnosticHelper(pid.pid());
            try (CollectedData result = req.collectWindow(helper, 0, duration.toMillis())) {
                if ("true".equals(result.metadata().get("skip"))) {
                    System.err.println("Error: profiling was skipped: " + result.metadata().getOrDefault("reason", "unknown"));
                    return 1;
                }
                Path outputPath = Paths.get(outputFile).toAbsolutePath();
                if (outputPath.getParent() != null) Files.createDirectories(outputPath.getParent());
                if (result.tempFiles().containsKey("flame")) {
                    Files.copy(result.tempFiles().get("flame"), outputPath,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.writeString(outputPath, result.rawData(), java.nio.charset.StandardCharsets.UTF_8);
                }
                System.out.println("\n✓ Flamegraph successfully generated!");
                reportOutput(outputPath);
            } finally {
                helper.cleanup();
            }
            return 0;
        } catch (IllegalArgumentException e) {
            System.err.println("Error parsing duration or interval: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            System.err.println("Error executing async-profiler: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            return 1;
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

    private int useReplayFile(Path replayFile) {
        try {
            ReplayProvider replay = new ReplayProvider(replayFile);

            long targetPid = resolveReplayPid(replay);
            if (targetPid < 0) return 1;

            ReplayProvider.FlamegraphData flamegraph = replay.getFlamegraph(targetPid);
            if (flamegraph == null) {
                System.err.println("Error: replay file does not contain a flamegraph for PID " + targetPid);
                return 1;
            }

            Path outputPath = Paths.get(outputFile).toAbsolutePath();
            if (outputPath.getParent() != null) Files.createDirectories(outputPath.getParent());
            flamegraph.writeTo(outputPath);

            System.out.println("\n✓ Flamegraph successfully extracted from replay file!");
            System.out.println("PID: " + targetPid);
            String fg_event = flamegraph.getEvent();
            if (!fg_event.equals("unknown")) System.out.println("Event: " + fg_event);
            String fg_duration = flamegraph.getDuration();
            if (fg_duration != null) System.out.println("Duration: " + fg_duration);
            String fg_interval = flamegraph.getInterval();
            if (fg_interval != null) System.out.println("Interval: " + fg_interval);
            reportOutput(outputPath);
            return 0;
        } catch (IOException e) {
            System.err.println("Error reading replay file: " + e.getMessage());
            return 1;
        }
    }

    /** Resolves the target PID from a replay file, returns -1 on error. */
    private long resolveReplayPid(ReplayProvider replay) {
        List<JVMDiscovery.JVMProcess> jvms = replay.listRecordedJvms(null);
        if (jvms.isEmpty()) {
            System.err.println("Error: replay file does not contain any recorded JVMs");
            return -1;
        }
        List<JVMDiscovery.JVMProcess> candidates = (target == null) ? jvms : jvms.stream()
            .filter(j -> String.valueOf(j.pid()).equals(target) ||
                         j.mainClass().toLowerCase().contains(target.toLowerCase()))
            .toList();
        if (candidates.isEmpty()) {
            System.err.println("Error: no JVM matching '" + target + "' found in replay file");
            jvms.forEach(j -> System.err.println("  PID " + j.pid() + ": " + j.mainClass()));
            return -1;
        }
        if (candidates.size() > 1) {
            String msg = target == null ? "replay file contains multiple JVMs, please specify a target"
                                        : "multiple JVMs match '" + target + "'";
            System.err.println("Error: " + msg);
            candidates.forEach(j -> System.err.println("  PID " + j.pid() + ": " + j.mainClass()));
            return -1;
        }
        return candidates.get(0).pid();
    }

    /** Prints output file info and optionally opens it in a browser. */
    private void reportOutput(Path outputPath) throws IOException {
        System.out.println("Output file: " + outputPath);
        System.out.println("File size: " + Files.size(outputPath) + " bytes");
        if (open) {
            if (openInBrowser(outputPath)) {
                System.out.println("\nOpened flamegraph in browser.");
            } else {
                System.out.println("\nCould not automatically open browser. Open manually with:");
                System.out.println("  open " + outputPath + "  (macOS)");
                System.out.println("  xdg-open " + outputPath + "  (Linux)");
            }
        }
    }
}