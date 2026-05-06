package me.bechberger.jstall.cli;

import me.bechberger.jstall.Main;
import me.bechberger.jstall.analyzer.Analyzer;
import me.bechberger.jstall.analyzer.AnalyzerOutput;
import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.cli.live.InteractiveRenderer;
import me.bechberger.jstall.cli.live.KeyEvent;
import me.bechberger.jstall.cli.live.LiveViewState;
import me.bechberger.jstall.cli.live.RawTerminal;
import me.bechberger.jstall.cli.live.TableViewModel;
import me.bechberger.jstall.model.SystemEnvironment;
import me.bechberger.jstall.model.ThreadDumpSnapshot;
import me.bechberger.jstall.provider.DataCollector;
import me.bechberger.jstall.provider.RecordingProvider;
import me.bechberger.jstall.provider.requirement.CollectedData;
import me.bechberger.jstall.provider.requirement.DataRequirement;
import me.bechberger.jstall.provider.requirement.DataRequirements;
import me.bechberger.jstall.provider.requirement.ThreadDumpRequirement;
import me.bechberger.jstall.util.CommandExecutor;
import me.bechberger.jstall.util.JMXDiagnosticHelper;
import me.bechberger.jstall.util.JVMDiscovery;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * Runs an analyzer in live/watch mode: repeatedly collects data, analyzes,
 * clears the screen and displays the result. On shutdown (Ctrl+C), optionally
 * persists the last N samples as a recording ZIP.
 */
public class LiveModeRunner {

    private static final String CLEAR_SCREEN = "\033[2J\033[H";
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CommandExecutor executor;
    private final long pid;
    private final String mainClass;
    private final Analyzer analyzer;
    private final Map<String, Object> options;
    private volatile Duration liveInterval;
    private final int keepSamples;
    private final boolean colorEnabled;

    private volatile boolean running = true;
    private final ArrayDeque<LiveSample> sampleBuffer;
    private int sampleCount = 0;
    private volatile long lastCollectionMs = -1;
    private volatile long lastAnalysisMs = -1;
    private volatile boolean forceCollection = false;
    private boolean secondarySortPending = false;
    private Map<DataRequirement, List<CollectedData>> previousRawCollected = null;
    private Map<String, List<CollectedData>> previousByType = null;
    private JMXDiagnosticHelper helper;
    private ScheduledExecutorService sharedScheduler;
    private ExecutorService collectionExecutor;
    private volatile RawTerminal activeTerminal;

    record LiveSample(Instant timestamp, Map<DataRequirement, List<CollectedData>> rawCollected,
                      Map<String, List<CollectedData>> collectedByType, AnalyzerResult result) {
    }

    public LiveModeRunner(CommandExecutor executor, long pid, String mainClass,
                          Analyzer analyzer, Map<String, Object> options,
                          Duration liveInterval, int keepSamples, boolean colorEnabled) {
        this.executor = executor;
        this.pid = pid;
        this.mainClass = mainClass;
        this.analyzer = analyzer;
        this.options = options;
        this.liveInterval = liveInterval;
        this.keepSamples = keepSamples;
        this.colorEnabled = colorEnabled;
        this.sampleBuffer = new ArrayDeque<>(keepSamples > 0 ? keepSamples + 1 : 4);
    }

    public int run() {
        Thread shutdownHook = new Thread(this::onShutdown);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        this.helper = executor.diagnosticHelper(pid);
        this.sharedScheduler = Executors.newScheduledThreadPool(2);
        this.collectionExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "jstall-collection");
            t.setDaemon(true);
            return t;
        });

        int lastExitCode;
        if (RawTerminal.isInteractiveSupported()) {
            lastExitCode = runInteractive(shutdownHook);
        } else {
            lastExitCode = runSimple(shutdownHook);
        }
        return lastExitCode;
    }

    private int runSimple(Thread shutdownHook) {
        int lastExitCode = 0;
        try {
            while (running) {
                long cycleStart = System.currentTimeMillis();
                lastExitCode = collectAndDisplay();
                if (!running) break;

                long elapsed = System.currentTimeMillis() - cycleStart;
                long sleepMs = liveInterval.toMillis() - elapsed;
                if (sleepMs > 0) {
                    Thread.sleep(sleepMs);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("\nLive mode error: " + e.getMessage());
            lastExitCode = 1;
        } finally {
            cleanupAfterRun(shutdownHook);
        }
        return lastExitCode;
    }

    private int runInteractive(Thread shutdownHook) {
        int lastExitCode = 0;
        RawTerminal terminal = new RawTerminal();
        this.activeTerminal = terminal;
        InteractiveRenderer renderer = new InteractiveRenderer(terminal);
        LiveViewState viewState = LiveViewState.empty();

        try {
            terminal.enter();
            renderer.setStatusInfo("PID " + pid + " (" + mainClass + ")");

            // Show placeholder tabs immediately before data collection
            if (analyzer instanceof me.bechberger.jstall.analyzer.impl.StatusAnalyzer statusAnalyzer) {
                AnalyzerOutput placeholder = statusAnalyzer.buildPlaceholderOutput(options);
                viewState = applyColor(LiveViewState.fromStructured(placeholder));
                renderFrame(renderer, viewState);
            }

            // Collect first sample asynchronously so the UI stays responsive
            Future<Integer> pendingCollection = collectionExecutor.submit(this::collectSample);
            long lastCollectionTime = System.currentTimeMillis();

            while (running) {
                // Process all pending input before rendering
                KeyEvent key = terminal.readKey(100);
                boolean needsRender = false;
                while (key != null) {
                    boolean quit = handleKey(key, renderer, viewState.model());
                    if (quit) {
                        running = false;
                        break;
                    }
                    viewState = applyColor(applyTabKey(key, viewState, renderer));
                    needsRender = true;
                    // Drain remaining keys without waiting
                    key = terminal.readKey(0);
                }
                if (!running) break;

                if (needsRender) {
                    // Update model filter from renderer
                    if (viewState.model() != null) {
                        viewState.model().setFilter(renderer.getFilterInput());
                    }
                    renderFrame(renderer, viewState);
                }

                // Check if async collection finished
                if (pendingCollection != null && pendingCollection.isDone()) {
                    try {
                        lastExitCode = pendingCollection.get();
                    } catch (ExecutionException e) {
                        // Error already buffered in collectSample
                    }
                    pendingCollection = null;
                    lastCollectionTime = System.currentTimeMillis();
                    if (!sampleBuffer.isEmpty()) {
                        viewState = applyColor(viewState.refreshFromResult(sampleBuffer.peekLast().result()));
                    }
                    renderFrame(renderer, viewState);
                }

                // Check if it's time to collect a new sample (or forced)
                if (pendingCollection == null) {
                    long elapsed = System.currentTimeMillis() - lastCollectionTime;
                    if (forceCollection || elapsed >= liveInterval.toMillis()) {
                        forceCollection = false;
                        pendingCollection = collectionExecutor.submit(this::collectSample);
                    }
                }
            }
        } catch (Exception e) {
            // Close terminal before printing error
            System.err.println("\nLive mode error: " + e.getMessage());
            lastExitCode = 1;
        } finally {
            terminal.close();
            activeTerminal = null;
            cleanupAfterRun(shutdownHook);
        }

        return lastExitCode;
    }

    private LiveViewState applyColor(LiveViewState state) {
        if (colorEnabled && state.model() != null) {
            state.model().setColorEnabled(true);
        }
        return state;
    }

    private LiveViewState applyTabKey(KeyEvent key, LiveViewState current, InteractiveRenderer renderer) {
        if (key instanceof KeyEvent.Special s) {
            if (s.type() == KeyEvent.Type.TAB) {
                return current.withNextTab();
            }
            if (s.type() == KeyEvent.Type.SHIFT_TAB) {
                return current.withPreviousTab();
            }
        }
        return current;
    }

    private void renderFrame(InteractiveRenderer renderer, LiveViewState viewState) {
        String timing = lastCollectionMs >= 0
                ? String.format("⏱ %dms", lastCollectionMs + Math.max(lastAnalysisMs, 0))
                : "⏱ …";
        renderer.render(
                viewState.model(),
                viewState.displayOutput(),
                sampleCount,
                (int) liveInterval.getSeconds(),
                viewState.tabNames(),
                viewState.activeTab(),
                timing);
    }

    private void cleanupAfterRun(Thread shutdownHook) {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM is already shutting down
        }
        shutdownScheduler();
        persistIfNeeded();
    }

    private int collectSample() {
        try {
            long t0 = System.currentTimeMillis();
            var currentSample = collectSingleSample();
            long t1 = System.currentTimeMillis();
            lastCollectionMs = t1 - t0;
            sampleCount++;
            Instant now = Instant.now();

            AnalyzerResult result;
            if (previousByType == null) {
                previousRawCollected = currentSample.rawCollected;
                previousByType = currentSample.byType;
                result = analyzeGracefully(currentSample.byType);
                bufferSample(now, currentSample.rawCollected, currentSample.byType, result);
            } else {
                var mergedByType = CollectedDataHelper.merge(previousByType, currentSample.byType);
                var mergedRaw = CollectedDataHelper.merge(previousRawCollected, currentSample.rawCollected);
                result = analyze(mergedByType);
                bufferSample(now, mergedRaw, mergedByType, result);
                previousRawCollected = currentSample.rawCollected;
                previousByType = currentSample.byType;
            }
            lastAnalysisMs = System.currentTimeMillis() - t1;
            return result.exitCode();
        } catch (Exception e) {
            sampleCount++;
            // Buffer an error result so the interactive mode can display it
            String errorMsg = "Error collecting data: " + e.getMessage()
                    + "\n\n(Will retry on next interval)";
            if (isJvmGone(e)) {
                errorMsg = "Target JVM (PID " + pid + ") appears to have exited.\n\n" + e.getMessage();
                running = false;
            } else {
                try {
                    helper = executor.reconnectDiagnosticHelper(pid);
                } catch (Exception ignored) {}
            }
            bufferSample(Instant.now(), Map.of(), Map.of(),
                    AnalyzerResult.withExitCode(errorMsg, 1));
            return 1;
        }
    }

    /**
     * Handles a key event. Returns true if we should quit.
     */
    private boolean handleKey(KeyEvent key, InteractiveRenderer renderer, TableViewModel model) {
        if (renderer.isFilterMode()) {
            return handleFilterKey(key, renderer, model);
        }

        if (key instanceof KeyEvent.Char c) {
            char ch = c.ch();
            // Secondary sort mode: 's' was pressed previously, now expecting a digit
            if (secondarySortPending) {
                secondarySortPending = false;
                if (ch >= '1' && ch <= '9') {
                    if (model != null) model.addSecondarySort(ch - '1');
                }
                return false;
            }
            switch (ch) {
                case 'q', 'Q' -> { return true; }
                case 'j' -> { if (model != null) model.scroll(1); }
                case 'k' -> { if (model != null) model.scroll(-1); }
                case 'h' -> { if (model != null) model.scrollHorizontal(-4); }
                case 'l' -> { if (model != null) model.scrollHorizontal(4); }
                case '/' -> renderer.enterFilterMode();
                case 'r', 'R' -> forceCollection = true;
                case 's' -> secondarySortPending = true;
                case '+', '=' -> {
                    long secs = liveInterval.getSeconds();
                    liveInterval = Duration.ofSeconds(Math.min(secs + 1, 300));
                }
                case '-', '_' -> {
                    long secs = liveInterval.getSeconds();
                    liveInterval = Duration.ofSeconds(Math.max(secs - 1, 1));
                }
                case '1','2','3','4','5','6','7','8','9' -> {
                    if (model != null) model.toggleSort(ch - '1');
                }
                default -> {}
            }
        } else if (key instanceof KeyEvent.Special s) {
            secondarySortPending = false;
            switch (s.type()) {
                case UP -> { if (model != null) model.scroll(-1); }
                case DOWN -> { if (model != null) model.scroll(1); }
                case LEFT -> { if (model != null) model.scrollHorizontal(-4); }
                case RIGHT -> { if (model != null) model.scrollHorizontal(4); }
                case PAGE_UP -> { if (model != null) model.scroll(-20); }
                case PAGE_DOWN -> { if (model != null) model.scroll(20); }
                case HOME -> { if (model != null) model.scrollToTop(); }
                case END -> { if (model != null) model.scrollToBottom(); }
                case ESCAPE -> {
                    // Clear filter
                    renderer.setFilterInput("");
                    if (model != null) model.setFilter("");
                }
                default -> {}
            }
        }
        return false;
    }

    private boolean handleFilterKey(KeyEvent key, InteractiveRenderer renderer, TableViewModel model) {
        if (key instanceof KeyEvent.Char c) {
            renderer.appendFilterChar(c.ch());
        } else if (key instanceof KeyEvent.Special s) {
            switch (s.type()) {
                case ENTER, ESCAPE -> renderer.exitFilterMode();
                case BACKSPACE -> renderer.backspaceFilter();
                default -> {}
            }
        }
        return false;
    }

    private void shutdownScheduler() {
        if (collectionExecutor != null) {
            collectionExecutor.shutdownNow();
        }
        if (sharedScheduler != null) {
            sharedScheduler.shutdown();
            try {
                if (!sharedScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    sharedScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                sharedScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private int collectAndDisplay() {
        try {
            var currentSample = collectSingleSample();
            sampleCount++;
            Instant now = Instant.now();

            if (previousByType == null) {
                previousRawCollected = currentSample.rawCollected;
                previousByType = currentSample.byType;
                AnalyzerResult result = analyzeGracefully(currentSample.byType);
                bufferSample(now, currentSample.rawCollected, currentSample.byType, result);
                display(now, result.shouldDisplay() ? result.output() : null);
                return result.exitCode();
            }

            // Merge previous + current for comparative analysis
            var mergedByType = CollectedDataHelper.merge(previousByType, currentSample.byType);
            var mergedRaw = CollectedDataHelper.merge(previousRawCollected, currentSample.rawCollected);

            AnalyzerResult result = analyze(mergedByType);
            bufferSample(now, mergedRaw, mergedByType, result);

            // Shift: current becomes previous for next cycle
            previousRawCollected = currentSample.rawCollected;
            previousByType = currentSample.byType;

            display(now, result.shouldDisplay() ? result.output() : null);
            return result.exitCode();
        } catch (Exception e) {
            sampleCount++;
            display(Instant.now(), null);
            System.err.println("Error collecting data: " + e.getMessage());
            if (isJvmGone(e)) {
                System.err.println("Target JVM (PID " + pid + ") appears to have exited.");
                running = false;
            } else {
                // Attempt to get a fresh connection for next cycle
                try {
                    helper = executor.reconnectDiagnosticHelper(pid);
                } catch (Exception reconnectErr) {
                    // Ignore, will retry next cycle with stale helper
                }
            }
            return 1;
        }
    }

    private record RawSample(Map<DataRequirement, List<CollectedData>> rawCollected,
                             Map<String, List<CollectedData>> byType) {}

    private RawSample collectSingleSample() throws IOException {
        Map<String, Object> singleSampleOptions = new HashMap<>(options);
        singleSampleOptions.put("dump-count", 1);
        DataRequirements requirements = analyzer.getDataRequirements(singleSampleOptions);
        DataCollector collector = new DataCollector(helper, requirements, sharedScheduler);
        Map<DataRequirement, List<CollectedData>> rawCollected = collector.collectAll();
        Map<String, List<CollectedData>> byType = CollectedDataHelper.toByTypeMap(rawCollected);
        return new RawSample(rawCollected, byType);
    }

    private AnalyzerResult analyze(Map<String, List<CollectedData>> mergedByType) {
        List<CollectedData> dumpData = mergedByType.getOrDefault(ThreadDumpRequirement.TYPE, List.of());
        Map<String, String> systemProps = CollectedDataHelper.extractSystemProps(mergedByType);
        List<ThreadDumpSnapshot> threadDumps = ThreadDumpRequirement.toSnapshots(
                dumpData, systemProps, SystemEnvironment.create(executor));
        ResolvedData data = ResolvedData.fromDumpsAndCollectedData(threadDumps, mergedByType);
        return analyzer.analyze(data, options);
    }

    /**
     * Runs the analyzer, catching insufficient-dump errors gracefully.
     * For MANY analyzers on first sample, returns a "Collecting data..." placeholder.
     */
    private AnalyzerResult analyzeGracefully(Map<String, List<CollectedData>> byType) {
        try {
            return analyze(byType);
        } catch (IllegalArgumentException e) {
            // Single MANY analyzer with insufficient dumps
            return AnalyzerResult.ok("Collecting data...\n\nNeed 2 samples before analyzing '" + analyzer.name() + "'.");
        }
    }

    private void bufferSample(Instant now, Map<DataRequirement, List<CollectedData>> mergedRaw,
                              Map<String, List<CollectedData>> mergedByType, AnalyzerResult result) {
        // Always buffer at least 1 sample for interactive display; respect keepSamples for persistence
        int maxBuffer = Math.max(keepSamples, 1);
        if (sampleBuffer.size() >= maxBuffer) {
            sampleBuffer.pollFirst();
        }
        sampleBuffer.addLast(new LiveSample(now, mergedRaw, mergedByType, result));
    }

    private void display(Instant now, String body) {
        System.out.print(CLEAR_SCREEN);
        System.out.println(formatHeader(now));
        System.out.println();
        if (body != null) {
            System.out.println(body);
        }
        System.out.flush();
    }

    private String formatHeader(Instant now) {
        String ts = LocalDateTime.ofInstant(now, java.time.ZoneId.systemDefault()).format(TIMESTAMP_FMT);
        return String.format("Live: %s | PID %d (%s) | Sample #%d | Every %s | Ctrl+C to stop",
                ts, pid, mainClass, sampleCount, formatDuration(liveInterval));
    }

    private static String formatDuration(Duration d) {
        long ms = d.toMillis();
        if (ms % 1000 == 0) {
            return (ms / 1000) + "s";
        }
        return ms + "ms";
    }

    private boolean isJvmGone(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("no such process") ||
               lower.contains("not found") ||
               lower.contains("connection refused") ||
               lower.contains("cannot attach") ||
               lower.contains("not running");
    }

    private void onShutdown() {
        running = false;
        // Ensure terminal is restored even if JVM exits abruptly
        RawTerminal t = activeTerminal;
        if (t != null) {
            t.close();
        }
    }

    private void persistIfNeeded() {
        if (keepSamples <= 0 || sampleBuffer.isEmpty()) {
            return;
        }
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path outputFile = Path.of("live-recording-" + pid + "-" + timestamp + ".zip");

            // Build collected data in the format RecordingProvider expects
            Map<DataRequirement, List<CollectedData>> mergedData = new LinkedHashMap<>();
            for (LiveSample sample : sampleBuffer) {
                for (Map.Entry<DataRequirement, List<CollectedData>> entry : sample.rawCollected().entrySet()) {
                    mergedData.computeIfAbsent(entry.getKey(), __ -> new ArrayList<>())
                              .addAll(entry.getValue());
                }
            }

            JVMDiscovery.JVMProcess process = new JVMDiscovery.JVMProcess(pid, mainClass);
            long startedAt = sampleBuffer.peekFirst().timestamp().toEpochMilli();
            long finishedAt = sampleBuffer.peekLast().timestamp().toEpochMilli();

            RecordingProvider.CollectedJvmData jvmData =
                    RecordingProvider.CollectedJvmData.success(process, mergedData, startedAt, finishedAt);

            DataRequirements requirements = analyzer.getDataRequirements(options);
            RecordingProvider provider = new RecordingProvider(executor, Main.VERSION);
            provider.writeSingleTargetRecording(jvmData, requirements, outputFile);

            System.err.println("\nSaved " + sampleBuffer.size() + " samples to " + outputFile.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("\nFailed to persist live samples: " + e.getMessage());
        }
    }
}
