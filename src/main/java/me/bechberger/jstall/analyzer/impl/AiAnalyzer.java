package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.BaseAnalyzer;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.provider.requirement.DataRequirements;
import me.bechberger.jstall.util.llm.AiChatLoop;
import me.bechberger.jstall.util.llm.AiTools;
import me.bechberger.jstall.util.llm.ContextCompressor;
import me.bechberger.jstall.util.llm.JstallCommandTool;
import me.bechberger.jstall.util.llm.LlmProvider;
import me.bechberger.jstall.util.llm.OpenAiLlmProvider;
import me.bechberger.jstall.util.llm.SourceTools;
import me.bechberger.jstall.util.llm.ToolDefinition;
import me.bechberger.jstall.util.llm.ToolExecutor;
import me.bechberger.jstall.analyzer.ThreadActivityCategorizer;
import me.bechberger.jstall.util.render.MarkdownRenderer;
import me.bechberger.jstall.util.render.AnsiCodes;
import me.bechberger.jstall.util.SystemAnalyzer;
import me.bechberger.jstall.util.CommandExecutor;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI-powered thread dump analyzer using LLM.
 * <p>
 * Runs StatusAnalyzer to get thread dump analysis, then sends it to an LLM
 * for intelligent insights and answers.
 */
public class AiAnalyzer extends BaseAnalyzer {

    /**
     * System prompt for both local and cloud providers.
     * <p>
     * /no_think suppresses Qwen3's chain-of-thought tokens so the model emits
     * its answer directly, cutting response time roughly in half on small models.
     * Pass think=true to omit the directive and let the model reason step-by-step.
     * Other models ignore the directive harmlessly.
     */
    private static String systemPrompt(boolean think) {
        return (think ? "" : "/no_think\n") +
            "You are a JVM performance expert. Analyze jstall thread dump data. " +
            "Report only what is in the data. No speculation. No generic advice. Be specific and brief.";
    }

    private static final String DEFAULT_USER_PROMPT =
        "One-sentence bottom line then bullet findings. Each bullet must cite a specific thread name, lock address, section, or number from the data above — never repeat a rule whose trigger is absent. Report EVERY rule that fires, not just the first one — multiple issues can coexist. Flag:\n" +
        "- deadlock section → report thread names + lock addresses first\n" +
        "- BLOCKED threads → Java monitor contention OR classloader lock (see BLOCKED thread stacks below)\n" +
        "- WAITING/TIMED_WAITING thread stacks contain 'waiting on the Class initialization monitor' → CLASS INITIALIZATION DEADLOCK — report as deadlock between the named classes\n" +
        "- PRODUCER thread with 'Lock Wait' → queue full (backpressure) — even at 0 CPU%, producer is blocked waiting for queue space\n" +
        "- WORKER/CONSUMER with 'Lock Wait' at 0 CPU% → idle (ok)\n" +
        "- WAITING/TIMED_WAITING thread stacks contain Semaphore.acquire / acquireSharedInterruptibly → semaphore/pool exhaustion — name the specific threads\n" +
        "- WAITING/TIMED_WAITING thread stacks contain hikari / HikariCP / getConnection → connection pool exhaustion\n" +
        "- WAITING/TIMED_WAITING thread stacks contain RateLimiter / acquirePermission / AtomicRateLimiter → rate limiter queue full\n" +
        "- WAITING/TIMED_WAITING thread stacks contain GenericObjectPool / borrowObject / commons.pool → Apache Commons Pool exhaustion\n" +
        "- TIMED_WAITING thread stacks contain ForkJoinTask.get / ForkJoinTask.awaitDone + ForkJoinPool workers → ForkJoin pool saturation / managed block\n" +
        "- thread-count-warning section → thread leak\n" +
        "- WAITING/TIMED_WAITING thread details pre-fetched below → classify by thread name and stack: pool/worker/executor names = pool starvation; sleeper-style names = idle/healthy\n" +
        "- RUNNABLE thread details pre-fetched below → classify by stack frames: socketRead0/readBytes/native methods = blocked I/O / native wait\n" +
        "- heap Δ > +5 MiB in 5s → memory leak / allocation spike\n" +
        "- heap used% > 80% → high memory pressure\n" +
        "- thread with Computation + CPU% > 80% → CPU hot-spot (REPORT THIS EVEN IF A DEADLOCK IS ALSO PRESENT)\n";

    private final LlmProvider llmProvider;
    private final CommandExecutor commandExecutor;

    public AiAnalyzer(LlmProvider llmProvider) {
        this(llmProvider, new CommandExecutor.LocalCommandExecutor());
    }

    public AiAnalyzer(LlmProvider llmProvider, CommandExecutor commandExecutor) {
        this.llmProvider = llmProvider;
        this.commandExecutor = commandExecutor;
    }

    @Override
    public String name() {
        return "ai";
    }

    @Override
    public Set<String> supportedOptions() {
        // Support all status options plus AI-specific ones
        Set<String> options = new java.util.HashSet<>(new StatusAnalyzer().supportedOptions());
        options.add("model");
        options.add("question");
        options.add("raw");
        options.add("dry-run");
        options.add("short");
        options.add("think");
        options.add("tools");
        options.add("no-tools");
        options.add("source-root");
        options.add("pretty");
        options.add("no-pretty");
        options.add("allow-mutations");
        options.add("chat");
        options.add("verbose");
        options.add("save");
        return options;
    }

    @Override
    public DumpRequirement dumpRequirement() {
        return DumpRequirement.MANY;
    }

    @Override
    public DataRequirements getDataRequirements(Map<String, Object> options) {
        return new StatusAnalyzer().getDataRequirements(options);
    }

    @Override
    public AnalyzerResult analyze(ResolvedData data, Map<String, Object> options) {
        // Extract AI-specific options
        String model = getStringOption(options, "model", "gpt-50-nano");
        String customQuestion = getStringOption(options, "question", null);
        boolean rawOutput = getBooleanOption(options, "raw", false);
        boolean dryRun = getBooleanOption(options, "dry-run", false);
        boolean shortMode = getBooleanOption(options, "short", false);
        boolean showThinking = getBooleanOption(options, "think", false);
        String sourceRoot = getStringOption(options, "source-root",
            System.getProperty("user.dir"));
        boolean prettyMode = getBooleanOption(options, "no-pretty", false) ? false
            : getBooleanOption(options, "pretty", System.console() != null);
        boolean allowMutations = getBooleanOption(options, "allow-mutations", false);
        boolean chatMode = getBooleanOption(options, "chat", false);
        boolean verbose = getBooleanOption(options, "verbose", false);
        String saveTo = getStringOption(options, "save", null);
        @SuppressWarnings("unchecked")
        List<String> targets = options.get("targets") instanceof List<?> rawList
            ? (List<String>) rawList : List.of();
        // Tools default to ON for any provider that supports the OpenAI tool-call shape.
        // The chat-with-tool-loop path requires OpenAiLlmProvider regardless; for other
        // providers we silently skip tool registration further below.
        boolean useTools;
        if (getBooleanOption(options, "no-tools", false)) {
            useTools = false;
        } else if (options.containsKey("tools")) {
            useTools = getBooleanOption(options, "tools", false);
        } else {
            useTools = true;
        }

        // Enable intelligent filtering by default
        Map<String, Object> statusOptions = new HashMap<>(options);

        if (verbose) System.err.println("[ai] Collecting thread dumps and running analysis...");

        // Run status analyzer to get thread dump analysis
        StatusAnalyzer statusAnalyzer = new StatusAnalyzer();
        AnalyzerResult statusResult = statusAnalyzer.analyze(data, statusOptions);

        // Treat any exit code < 3 as valid (0=ok, 2=deadlock found, 10=jvm outdated, etc.)
        // Only truly fatal errors (e.g. could not connect to JVM) should abort AI analysis.
        if (statusResult.output() == null || statusResult.output().isBlank()) {
            return AnalyzerResult.withExitCode(
                "Failed to collect thread dump data.",
                1
            );
        }

        String analysis = statusResult.output();

        // Compress context for local providers (small models, token budget matters)
        boolean compact = (llmProvider instanceof OpenAiLlmProvider);

        // Build prompts. In chat mode the first turn uses a short-summary prompt so
        // the REPL prompt appears quickly; tools and the bullet-rules prompt are
        // reserved for follow-up turns once the user starts asking questions.
        String userPrompt = chatMode
            ? buildChatSeedPrompt(analysis, customQuestion, compact, targets)
            : buildUserPrompt(analysis, customQuestion, compact, targets);

        // Dry-run mode: just print the prompt without calling the API
        if (dryRun) {
            String output = "=== DRY RUN MODE ===\n\n" +
                            "Model: " + model + "\n\n" +
                            "System Prompt:\n" +
                            systemPrompt(showThinking) + "\n\n" +
                            "User Prompt:\n" +
                            userPrompt + "\n" +
                            "\n=== END DRY RUN ===\n";
            return AnalyzerResult.ok(output);
        }

        // Call LLM API
        try {
            String aiAnalysis = callLLM(model, userPrompt, rawOutput, showThinking, useTools, shortMode, data,
                sourceRoot, prettyMode, allowMutations, chatMode, verbose, targets);

            // If short mode, run a second pass for a succinct summary — but only if the
            // first-pass output is long enough to be worth compressing. The `--short`
            // user prompt already biases the model toward a one-line bottom-line + bullets,
            // and re-summarizing a 1 KB output with thinking enabled can take 30+ minutes
            // on local 9B models with negligible benefit.
            if (shortMode && !rawOutput && aiAnalysis != null && aiAnalysis.length() > SHORT_SUMMARY_THRESHOLD) {
                aiAnalysis = createShortSummary(model, aiAnalysis, false, showThinking);
            }
            if (saveTo != null && !saveTo.isBlank() && aiAnalysis != null && !aiAnalysis.isBlank()) {
                try {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(saveTo), aiAnalysis);
                    System.err.println("[ai] Saved analysis to " + saveTo);
                } catch (IOException e) {
                    System.err.println("[ai] Failed to save analysis: " + e.getMessage());
                }
            }
            if (llmProvider.supportsStreaming()) {
                return AnalyzerResult.ok(""); // Output already printed during streaming
            }
            return AnalyzerResult.ok(aiAnalysis);

        } catch (LlmProvider.LlmException e) {
            if (e.isAuthError()) {
                return AnalyzerResult.withExitCode(
                    "Authentication failed: " + e.getMessage() + "\nPlease check your API key.",
                    4
                );
            } else {
                return AnalyzerResult.withExitCode(
                    "LLM error: " + e.getMessage(),
                    5
                );
            }
        } catch (IOException e) {
            return AnalyzerResult.withExitCode(
                "Network error: " + e.getMessage(),
                3
            );
        }
    }

    private String buildUserPrompt(String analysis, String customQuestion, boolean compact) {
        return buildUserPrompt(analysis, customQuestion, compact, List.of());
    }

    private String buildUserPrompt(String analysis, String customQuestion, boolean compact, List<String> targets) {
        String context = compact ? ContextCompressor.compress(analysis) : analysis;
        StringBuilder prompt = new StringBuilder();
        if (!targets.isEmpty()) {
            prompt.append("Target JVM: ").append(String.join(", ", targets)).append("\n\n");
        }
        prompt.append("Thread Dump Analysis:\n");
        prompt.append("---\n");
        prompt.append(context);
        prompt.append("\n---\n\n");
        prompt.append(DEFAULT_USER_PROMPT);
        if (compact) {
            prompt.append("\n[Stacks truncated to ")
                  .append(ContextCompressor.MAX_STACK_FRAMES)
                  .append(" frames; top threads by CPU shown only]\n");
        }

        // Add provider-specific instructions
        String additionalInstructions = llmProvider.getAdditionalInstructions();
        if (!additionalInstructions.isEmpty()) {
            prompt.append("\nAdditional Instructions:\n");
            prompt.append(additionalInstructions);
            prompt.append("\n");
        }

        if (customQuestion != null && !customQuestion.trim().isEmpty()) {
            prompt.append("\n\nUser's specific question: ");
            prompt.append(customQuestion.trim());
        }

        return prompt.toString();
    }

    /**
     * First-turn prompt for chat mode: produce a short summary instead of the full
     * bullet-rules analysis. Keeps the same status-data context in conversation
     * history (so follow-up questions still have all the data available), but asks
     * for a 3-5 bullet summary so the REPL prompt appears quickly.
     */
    private String buildChatSeedPrompt(String analysis, String customQuestion, boolean compact, List<String> targets) {
        String context = compact ? ContextCompressor.compress(analysis) : analysis;
        StringBuilder prompt = new StringBuilder();
        if (!targets.isEmpty()) {
            prompt.append("Target JVM: ").append(String.join(", ", targets)).append("\n\n");
        }
        prompt.append("Thread Dump Analysis:\n");
        prompt.append("---\n");
        prompt.append(context);
        prompt.append("\n---\n\n");
        prompt.append("Provide a succinct summary of the current state of the application. ");
        prompt.append("Focus on the most important findings. Be specific and data-driven. ");
        prompt.append("Keep it to 3-5 bullet points. State the state, do not give advice.\n");
        prompt.append("The user will ask follow-up questions; do not invite them, just summarize.\n");
        if (compact) {
            prompt.append("\n[Stacks truncated to ")
                  .append(ContextCompressor.MAX_STACK_FRAMES)
                  .append(" frames; top threads by CPU shown only]\n");
        }

        String additionalInstructions = llmProvider.getAdditionalInstructions();
        if (!additionalInstructions.isEmpty()) {
            prompt.append("\nAdditional Instructions:\n");
            prompt.append(additionalInstructions);
            prompt.append("\n");
        }

        if (customQuestion != null && !customQuestion.trim().isEmpty()) {
            prompt.append("\n\nUser's specific question: ");
            prompt.append(customQuestion.trim());
        }

        return prompt.toString();
    }

    /**
     * Analyzes all JVMs on the system (full mode).
     *
     * @param count Number of dumps per JVM
     * @param intervalMs Interval between dumps in milliseconds
     * @param options Analysis options
     * @return Analyzer result with AI analysis of the entire system
     */
    public AnalyzerResult analyzeFullSystem(int count, long intervalMs, Map<String, Object> options) {
        // Extract AI-specific options
        String model = getStringOption(options, "model", "gpt-50-nano");
        String customQuestion = getStringOption(options, "question", null);
        boolean rawOutput = getBooleanOption(options, "raw", false);
        boolean dryRun = getBooleanOption(options, "dry-run", false);
        boolean shortMode = getBooleanOption(options, "short", false);
        boolean showThinking = getBooleanOption(options, "think", false);
        double cpuThreshold = getDoubleOption(options, "cpu-threshold", 1.0);

        // Enable intelligent filtering by default
        Map<String, Object> statusOptions = new HashMap<>(options);
        if (!statusOptions.containsKey("intelligent-filter")) {
            statusOptions.put("intelligent-filter", true);
        }

        // Analyze all JVMs
        SystemAnalyzer systemAnalyzer = new SystemAnalyzer(commandExecutor);
        List<SystemAnalyzer.JVMAnalysis> analyses;
        try {
            analyses = systemAnalyzer.analyzeAllJVMs(count, intervalMs, statusOptions, cpuThreshold);
        } catch (IOException e) {
            return AnalyzerResult.withExitCode(
                "Failed to analyze JVMs: " + e.getMessage(),
                1
            );
        }

        if (analyses.isEmpty()) {
            return AnalyzerResult.ok("No active JVMs found (CPU threshold: " + cpuThreshold + "%)");
        }

        System.err.println("Found " + analyses.size() + " active JVM(s)");
        System.err.println();

        // Build combined analysis
        String systemSummary = SystemAnalyzer.formatSystemSummary(analyses);
        String detailedAnalyses = SystemAnalyzer.formatDetailedAnalyses(analyses);

        // Build user prompt with system context
        String userPrompt = buildFullSystemPrompt(systemSummary, detailedAnalyses, customQuestion);

        // Dry-run mode: just print the prompt without calling the API
        if (dryRun) {
            String output = "=== DRY RUN MODE (FULL SYSTEM) ===\n\n" +
                            "Model: " + model + "\n\n" +
                            "System Prompt:\n" +
                            systemPrompt(showThinking) + "\n\n" +
                            "User Prompt:\n" +
                            userPrompt + "\n" +
                            "\n=== END DRY RUN ===\n";
            return AnalyzerResult.ok(output);
        }

        // Call LLM API
        try {
            String aiAnalysis = callLLM(model, userPrompt, rawOutput, showThinking, false, shortMode, null,
                null, false, false, false, false, List.of());

            // Skip second-pass summary when first-pass output is already concise — see
            // analyze() for rationale.
            if (shortMode && !rawOutput && aiAnalysis != null && aiAnalysis.length() > SHORT_SUMMARY_THRESHOLD) {
                aiAnalysis = createShortSummary(model, aiAnalysis, true, showThinking);
            }

            return AnalyzerResult.ok(aiAnalysis);

        } catch (LlmProvider.LlmException e) {
            if (e.isAuthError()) {
                return AnalyzerResult.withExitCode(
                    "Authentication failed: " + e.getMessage() + "\nPlease check your API key.",
                    4
                );
            } else {
                return AnalyzerResult.withExitCode(
                    "LLM error: " + e.getMessage(),
                    5
                );
            }
        } catch (IOException e) {
            return AnalyzerResult.withExitCode(
                "Network error: " + e.getMessage(),
                3
            );
        }
    }

    private String buildFullSystemPrompt(String systemSummary, String detailedAnalyses, String customQuestion) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("System-wide Analysis of All JVM Applications:\n\n");
        prompt.append(systemSummary);
        prompt.append("\n\nDetailed Analysis of Each JVM:\n");
        prompt.append("---\n");
        prompt.append(detailedAnalyses);
        prompt.append("\n---\n\n");
        prompt.append("Please provide your analysis in the following structure:\n\n");
        prompt.append("1. Start with a high-level summary of the overall system state and bottom line takewaways\n");
        prompt.append("2. Identify any cross-JVM issues, bottlenecks, or interesting patterns\n");
        prompt.append("3. For each JVM provide a short analysis\n");
        prompt.append("Don't offer generic advice; focus on the specific findings from the analyses.\n");
        prompt.append("This is non-interactive; provide a complete answer based on the data provided. Omit calls for user input.\n");
        prompt.append("Give the JVMs nicer names, but omit sentences like 'Here is a concise, findings-focused analysis of the 5 active JVMs, with nicer names and non-generic guidance.'" +
                      " and omit trying to explain what any JVM is doing in general. Just use 'name (pid)'.\n");
        prompt.append("Don't use JVM1, ..., but 'name (pid)'.\n");
        prompt.append("Every fact needs to be based on the data provided; do not make up any facts or invent any findings" +
                      "and mention short reasons for every statement, be as specific as possible.\n");
        prompt.append("Be succinct and to the point, the user is focused on performance.\n");

        // Add provider-specific instructions
        String additionalInstructions = llmProvider.getAdditionalInstructions();
        if (!additionalInstructions.isEmpty()) {
            prompt.append("\nAdditional Instructions:\n");
            prompt.append(additionalInstructions);
            prompt.append("\n");
        }

        if (customQuestion != null && !customQuestion.trim().isEmpty()) {
            prompt.append("\n\nUser's specific question: ");
            prompt.append(customQuestion.trim());
        }

        return prompt.toString();
    }

    private double getDoubleOption(Map<String, Object> options, String key, double defaultValue) {
        Object value = options.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static final String TOOLS_SYSTEM_ADDENDUM =
        "\n\nTools are available for deeper investigation. Call them ONLY when the data is insufficient:\n" +
        "- If the context already has enough data to answer confidently, answer directly WITHOUT calling tools.\n" +
        "- Call `get_threads_by_state BLOCKED` only when you see BLOCKED threads and need more detail.\n" +
        "- Call `get_threads_by_state WAITING` IMMEDIATELY when the hidden-waiting-threads section is present — those threads are parked on a lock and this is real contention.\n" +
        "- Call `get_lock_info` only when you see unexplained BLOCKED threads and need to identify the lock holder.\n" +
        "- Call `get_dependency_tree` only to trace a specific deadlock or wait chain not visible in the data.\n" +
        "- Call `get_thread_stack_trace` only for a specific thread that appears stuck but has no stack in the data.\n" +
        "- Call `search_stack_frames` or source tools only when a class/method name needs clarification.\n" +
        "- Call `run_jstall_command` to fetch FRESH live data from the JVM. The other tools (get_*, search_*) operate on the dump captured at the start of the conversation; if the user asks about CURRENT state, recent changes, or the conversation has gone on for a while, prefer `run_jstall_command threads`, `most-work`, or `dependency-tree` for a new snapshot. Note: it takes ~5-15s per call.\n" +
        "Minimize tool calls. If the data is clear, answer immediately without tools.";

    private static final int DEFAULT_TOOL_ITERATIONS = 5;

    /** Skip the second-pass `--short` LLM call if the first-pass output is already this short.
     *  The first-pass user prompt already requests "one-sentence bottom line then bullets",
     *  so anything under ~1.5 KB is already a usable summary and re-running the model
     *  (especially with thinking enabled) is wasteful and can hang on local 9B models. */
    private static final int SHORT_SUMMARY_THRESHOLD = 1500;

    private String callLLM(String model, String userPrompt, boolean rawOutput, boolean showThinking,
                           boolean useTools, boolean shortMode, ResolvedData data,
                           String sourceRoot, boolean prettyMode, boolean allowMutations, boolean chatMode,
                           boolean verbose, List<String> targets)
            throws IOException, LlmProvider.LlmException {

        List<LlmProvider.Message> messages = new ArrayList<>();
        messages.add(new LlmProvider.Message("system", systemPrompt(showThinking) +
            (useTools ? TOOLS_SYSTEM_ADDENDUM : "")));
        messages.add(new LlmProvider.Message("user", userPrompt));

        // Use tool-calling loop if enabled and provider supports it
        if (useTools && !rawOutput && llmProvider instanceof OpenAiLlmProvider openAiProvider && data != null) {
            openAiProvider.setEnableThinking(showThinking);
            // Build combined tool list
            AiTools aiTools = new AiTools(data);

            // Pre-inject WAITING and RUNNABLE thread details proactively — the local 9B model
            // reliably misses these tool calls, so we resolve them before sending.
            String effectiveUserPrompt = userPrompt;
            StringBuilder injected = new StringBuilder();
            if (userPrompt.contains("=== hidden-waiting-threads ===")) {
                String waitingData = buildWaitingThreadsSummary(aiTools, data);
                injected.append("\nWAITING thread details (pre-fetched):\n").append(waitingData).append("\n")
                    .append("LOCK CONTENTION ANALYSIS (use this to interpret the stacks above):\n")
                    .append("- Stack ends with ArrayBlockingQueue.put OR LinkedBlockingQueue.put → producer blocked because queue is FULL (backpressure). Report as: producer blocked on full queue.\n")
                    .append("- Stack contains AbstractQueuedSynchronizer.acquire + ReentrantLock → threads competing for the SAME lock = lock contention.\n")
                    .append("- Stack ends with AbstractQueuedSynchronizer.acquireSharedInterruptibly + Semaphore → threads waiting for a semaphore/pool resource = pool or bulkhead exhaustion.\n")
                    .append("- Stack ends with BlockingQueue.take OR ForkJoinPool.awaitWork OR ThreadPoolExecutor.getTask → idle worker waiting for work (NOT a problem).\n")
                    .append("- Stack contains hikari / HikariCP / getConnection → HikariCP connection pool exhaustion.\n")
                    .append("- Stack contains RateLimiter / acquirePermission / AtomicRateLimiter → Resilience4j RateLimiter queue — report as rate limiter queue full.\n")
                    .append("- Stack contains GenericObjectPool / borrowObject → Apache Commons Pool2 exhaustion.\n")
                    .append("- 0 CPU% for all these patterns is NORMAL (LockSupport.park does not spin).\n");
            }
            if (userPrompt.contains("=== hidden-timed-waiting-threads ===")) {
                String timedData = buildTimedWaitingThreadsSummary(aiTools, data);
                injected.append("\nTIMED_WAITING thread details (pre-fetched):\n").append(timedData).append("\n")
                    .append("TIMED_WAITING CLASSIFICATION:\n")
                    .append("- Thread name contains pool/worker/ForkJoin → pool worker sleeping for work (check if tasks are piling up).\n")
                    .append("- Stack contains hikari / HikariCP / getConnection → HikariCP connection pool exhaustion — report thread names.\n")
                    .append("- Stack contains RateLimiter / acquirePermission / AtomicRateLimiter → Resilience4j RateLimiter queue — report as rate limiter queue full.\n")
                    .append("- Stack contains GenericObjectPool / borrowObject / commons.pool → Apache Commons Pool2 exhaustion.\n")
                    .append("- Stack ends with Semaphore.acquire → semaphore/bulkhead exhaustion — report thread names.\n")
                    .append("- Stack contains ForkJoinTask.get / ForkJoinTask.awaitDone → ForkJoin pool waiting on child task — report if all pool workers are blocked.\n")
                    .append("- Stack ends with Thread.sleep → intentional sleep (healthy unless unexpectedly long).\n")
                    .append("- Stack ends with Object.wait with timeout → timed lock wait.\n")
                    .append("- Stack ends with LockSupport.parkNanos → parked on a condition variable.\n");
            }
            if (userPrompt.contains("=== hidden-runnable-threads ===")) {
                String runnableData = buildRunnableThreadsSummary(aiTools, data);
                injected.append("\nRUNNABLE thread details (pre-fetched):\n").append(runnableData).append("\n")
                    .append("RUNNABLE CLASSIFICATION:\n")
                    .append("- Stack contains socketRead0/readBytes/FileInputStream.read/NativeMethodAccessor → blocked I/O (native wait).\n")
                    .append("- Stack contains java.lang.ref.Finalizer → Finalizer thread blocked waiting to run finalizers (check if fin-lock-holder thread holds the lock).\n")
                    .append("- Stack contains tight loop with no I/O → CPU hot-spot (spin).\n")
                    .append("- 0 CPU% with no I/O frames and no spin → idle/parked despite RUNNABLE state.\n");
            }
            // Pre-inject stacks for BLOCKED threads (not in hidden section — already in table,
            // but stacks are needed to distinguish classloader lock vs. monitor contention).
            {
                String blockedData = buildBlockedThreadsSummary(data);
                if (blockedData != null) {
                    injected.append("\nBLOCKED thread stacks (pre-fetched):\n").append(blockedData).append("\n")
                        .append("BLOCKED CLASSIFICATION:\n")
                        .append("- Stack contains Class.forName/ClassLoader.loadClass → classloader lock contention (multiple threads loading same class).\n")
                        .append("- Stack contains synchronized / ObjectMonitor → Java monitor contention (identify lock holder via get_lock_info).\n")
                        .append("- Stack ends with AbstractQueuedSynchronizer → ReentrantLock contention.\n");
                }
            }
            if (injected.length() > 0) {
                effectiveUserPrompt = userPrompt + injected;
                messages.set(messages.size() - 1, new LlmProvider.Message("user", effectiveUserPrompt));
            }

            List<ToolDefinition> tools = new ArrayList<>(aiTools.getToolDefinitions());

            // Skip tool schemas when the context is self-contained.
            // Saves ~2000 prefill tokens (~10-15s) on local models.
            // All hidden-* sections have been pre-injected above (lines ~504-543), so the
            // context is always self-contained after pre-injection. We only need tools when
            // there are BLOCKED threads that haven't been explained — which is never now.
            // BLOCKED threads are now pre-injected with stacks; no tool needed for them either.
            // hidden-* sections are all pre-injected inline above — no tool call needed for them.
            boolean contextSelfContained = true;
            if (contextSelfContained) {
                tools = null;
                if (verbose) System.err.println("[ai] Context self-contained — skipping tool schemas for speed.");
            }
            JstallCommandTool jstallTool = new JstallCommandTool(allowMutations, targets);
            if (tools != null) {
                tools.add(jstallTool.getToolDefinition());
            }

            // Add source exploration tools if a source root is configured
            ToolExecutor baseExecutor = aiTools.createExecutor();
            ToolExecutor combined;
            if (sourceRoot != null && !sourceRoot.isBlank()) {
                SourceTools sourceTools = new SourceTools(Path.of(sourceRoot));
                if (tools != null) tools.addAll(sourceTools.getToolDefinitions());
                ToolExecutor sourceExecutor = sourceTools.createExecutor();
                combined = call -> {
                    String name = call.name();
                    if (name.startsWith("list_source") || name.startsWith("read_source") || name.startsWith("grep_source")) {
                        return sourceExecutor.execute(call);
                    } else if ("run_jstall_command".equals(name)) {
                        return jstallTool.execute(call);
                    } else {
                        return baseExecutor.execute(call);
                    }
                };
            } else {
                combined = call ->
                    "run_jstall_command".equals(call.name())
                        ? jstallTool.execute(call)
                        : baseExecutor.execute(call);
            }
            if (verbose) {
                ToolExecutor inner = combined;
                combined = call -> {
                    if ("run_jstall_command".equals(call.name())) {
                        long start = System.nanoTime();
                        try {
                            return inner.execute(call);
                        } finally {
                            long ms = (System.nanoTime() - start) / 1_000_000;
                            System.err.println("[tool] run_jstall_command finished in " + ms + " ms");
                        }
                    }
                    return inner.execute(call);
                };
            }

            if (verbose) System.err.println("[ai] Sending to model with " + (tools != null ? tools.size() : 0) + " tools available...");

            java.util.function.Consumer<String> downstream = buildResponseHandler(prettyMode, verbose);
            LlmProvider.StreamHandlers handlers = new LlmProvider.StreamHandlers(
                downstream, buildThinkingHandler(showThinking));

            java.util.function.Consumer<String> verboseToolHandler = buildVerboseToolHandler(verbose, showThinking);

            if (chatMode) {
                // Turn 1: short summary only — no tools, so the REPL prompt appears fast.
                // Follow-up turns get the full tool list.
                openAiProvider.chatWithToolLoop(model, messages, null, combined, handlers, DEFAULT_TOOL_ITERATIONS, true, verboseToolHandler);
                System.out.println();
                new AiChatLoop(openAiProvider, model, messages, tools, combined, prettyMode, verbose, showThinking).run();
                return "";
            }

            String result = openAiProvider.chatWithToolLoop(model, messages, tools, combined, handlers, DEFAULT_TOOL_ITERATIONS, true, verboseToolHandler);
            System.out.println();
            return result;
        }

        if (rawOutput) {
            return llmProvider.getRawResponse(model, messages);
        } else {
            StringBuilder output = new StringBuilder();
            boolean suppressOutput = shortMode && llmProvider.supportsStreaming();

            if (llmProvider instanceof OpenAiLlmProvider openAiProvider) {
                openAiProvider.setEnableThinking(showThinking);
            }
            if (showThinking && !llmProvider.supportsStreaming()) {
                System.err.println("Note: --think mode not supported by this provider (no streaming support)");
            }
            if (verbose) System.err.println("[ai] Sending to model (streaming)...");

            LlmProvider.StreamHandlers handlers = createStreamHandlers(output, showThinking, suppressOutput);
            llmProvider.chat(model, messages, handlers);
            if (!suppressOutput) {
                System.out.println();
            }
            return output.toString();
        }
    }

    /**
     * Build a concise summary of WAITING threads with enough stack depth to distinguish
     * ReentrantLock/AQS contention from idle thread-pool workers.
     * Shows up to 20 threads, 6 frames each, skipping pure JVM housekeeping threads.
     */
    private static String buildWaitingThreadsSummary(AiTools aiTools, ResolvedData data) {
        if (data == null || data.dumps().isEmpty()) return aiTools.getThreadsByState("WAITING");

        var snapshot = data.dumps().get(data.dumps().size() - 1);
        var dump = snapshot.parsed();
        if (dump == null) return aiTools.getThreadsByState("WAITING");

        // JVM internal threads that are always WAITING — not app threads
        java.util.Set<String> jvmThreads = java.util.Set.of(
            "Finalizer", "Reference Handler", "Common-Cleaner", "Signal Dispatcher",
            "Notification Thread", "Attach Listener"
        );

        var waitingThreads = dump.threads().stream()
            .filter(t -> t.state() == Thread.State.WAITING)
            .filter(t -> !jvmThreads.contains(t.name()))
            .toList();

        if (waitingThreads.isEmpty()) return aiTools.getThreadsByState("WAITING");

        int limit = Math.min(waitingThreads.size(), 20);
        StringBuilder sb = new StringBuilder();
        sb.append(waitingThreads.size()).append(" app-thread(s) in WAITING state");
        if (waitingThreads.size() > limit) sb.append(" (showing first ").append(limit).append(")");
        sb.append(":\n\n");

        for (int i = 0; i < limit; i++) {
            var t = waitingThreads.get(i);
            ThreadActivityCategorizer.Category activity = ThreadActivityCategorizer.categorize(t);
            sb.append("\"").append(t.name()).append("\"");
            sb.append(" [activity: ").append(activity.getDisplayName()).append("]");
            t.getWaitedOnLock().ifPresent(lock ->
                sb.append(" [monitor: ").append(lock.className()).append("]"));
            sb.append("\n");
            if (t.stackTrace() != null && !t.stackTrace().isEmpty()) {
                var stack = t.stackTrace().stream().map(Object::toString).toList();
                // Show: all frames up to the first java.util.concurrent blocking call
                // that reveals WHAT the thread is blocked on (put, acquire, take, etc.)
                // This ensures we don't hide ArrayBlockingQueue.put behind ForkJoinPool frames.
                boolean foundBlockingFrame = false;
                int shown = 0;
                for (int j = 0; j < stack.size() && shown < 8; j++) {
                    String frame = stack.get(j);
                    sb.append("    at ").append(frame).append("\n");
                    shown++;
                    // Stop after the first frame that reveals the blocking primitive
                    if (!foundBlockingFrame && (
                            frame.contains("BlockingQueue.put") || frame.contains("BlockingQueue.take") ||
                            frame.contains("BlockingQueue.offer") || frame.contains("BlockingQueue.poll") ||
                            frame.contains("Semaphore.acquire") || frame.contains("Semaphore.tryAcquire") ||
                            frame.contains("ReentrantLock") || frame.contains("CountDownLatch") ||
                            frame.contains("Object.wait") || frame.contains("LockSupport.parkNanos"))) {
                        foundBlockingFrame = true;
                        if (stack.size() > shown)
                            sb.append("    ... ").append(stack.size() - shown).append(" more frames\n");
                        break;
                    }
                }
                if (!foundBlockingFrame && stack.size() > shown)
                    sb.append("    ... ").append(stack.size() - shown).append(" more frames\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Build a concise summary of RUNNABLE threads with 0% CPU — these are typically
     * blocked in native I/O, spinning, or (for Finalizer) waiting on a monitor.
     * Shows up to 15 threads, 5 frames each.
     */
    private static String buildRunnableThreadsSummary(AiTools aiTools, ResolvedData data) {
        if (data == null || data.dumps().isEmpty()) return aiTools.getThreadsByState("RUNNABLE");

        var snapshot = data.dumps().get(data.dumps().size() - 1);
        var dump = snapshot.parsed();
        if (dump == null) return aiTools.getThreadsByState("RUNNABLE");

        // JVM internal threads that are always listed but uninteresting (except Finalizer)
        java.util.Set<String> skipThreads = java.util.Set.of(
            "Reference Handler", "Common-Cleaner", "Signal Dispatcher",
            "Notification Thread", "Attach Listener"
        );

        var runnableThreads = dump.threads().stream()
            .filter(t -> t.state() == Thread.State.RUNNABLE)
            .filter(t -> !skipThreads.contains(t.name()))
            .toList();

        if (runnableThreads.isEmpty()) return aiTools.getThreadsByState("RUNNABLE");

        int limit = Math.min(runnableThreads.size(), 15);
        StringBuilder sb = new StringBuilder();
        sb.append(runnableThreads.size()).append(" thread(s) in RUNNABLE state");
        if (runnableThreads.size() > limit) sb.append(" (showing first ").append(limit).append(")");
        sb.append(":\n\n");

        for (int i = 0; i < limit; i++) {
            var t = runnableThreads.get(i);
            ThreadActivityCategorizer.Category activity = ThreadActivityCategorizer.categorize(t);
            sb.append("\"").append(t.name()).append("\"");
            sb.append(" [activity: ").append(activity.getDisplayName()).append("]");
            t.getWaitedOnLock().ifPresent(lock ->
                sb.append(" [blocked on: ").append(lock.className()).append("]"));
            sb.append("\n");
            if (t.stackTrace() != null && !t.stackTrace().isEmpty()) {
                var stack = t.stackTrace().stream().map(Object::toString).toList();
                int shown = Math.min(stack.size(), 5);
                for (int j = 0; j < shown; j++) {
                    sb.append("    at ").append(stack.get(j)).append("\n");
                }
                if (stack.size() > shown)
                    sb.append("    ... ").append(stack.size() - shown).append(" more frames\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Build a concise summary of TIMED_WAITING threads — these are often pool workers
     * sleeping on a task, sleeping via Thread.sleep, or waiting in timed lock operations.
     * Shows up to 15 threads, 5 frames each.
     */
    private static String buildTimedWaitingThreadsSummary(AiTools aiTools, ResolvedData data) {
        if (data == null || data.dumps().isEmpty()) return aiTools.getThreadsByState("TIMED_WAITING");

        var snapshot = data.dumps().get(data.dumps().size() - 1);
        var dump = snapshot.parsed();
        if (dump == null) return aiTools.getThreadsByState("TIMED_WAITING");

        java.util.Set<String> skipThreads = java.util.Set.of(
            "Reference Handler", "Common-Cleaner", "Signal Dispatcher",
            "Notification Thread", "Attach Listener", "Finalizer"
        );

        var timedWaitingThreads = dump.threads().stream()
            .filter(t -> t.state() == Thread.State.TIMED_WAITING)
            .filter(t -> !skipThreads.contains(t.name()))
            .toList();

        if (timedWaitingThreads.isEmpty()) return aiTools.getThreadsByState("TIMED_WAITING");

        int limit = Math.min(timedWaitingThreads.size(), 15);
        StringBuilder sb = new StringBuilder();
        sb.append(timedWaitingThreads.size()).append(" thread(s) in TIMED_WAITING state");
        if (timedWaitingThreads.size() > limit) sb.append(" (showing first ").append(limit).append(")");
        sb.append(":\n\n");

        for (int i = 0; i < limit; i++) {
            var t = timedWaitingThreads.get(i);
            ThreadActivityCategorizer.Category activity = ThreadActivityCategorizer.categorize(t);
            sb.append("\"").append(t.name()).append("\"");
            sb.append(" [activity: ").append(activity.getDisplayName()).append("]");
            t.getWaitedOnLock().ifPresent(lock ->
                sb.append(" [blocked on: ").append(lock.className()).append("]"));
            sb.append("\n");
            if (t.stackTrace() != null && !t.stackTrace().isEmpty()) {
                var stack = t.stackTrace().stream().map(Object::toString).toList();
                int shown = Math.min(stack.size(), 5);
                for (int j = 0; j < shown; j++) {
                    sb.append("    at ").append(stack.get(j)).append("\n");
                }
                if (stack.size() > shown)
                    sb.append("    ... ").append(stack.size() - shown).append(" more frames\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Build stacks for BLOCKED threads — these appear in the main table but without
     * their full stack traces, so the model can't distinguish classloader lock from
     * Java monitor contention. Returns null if there are no BLOCKED threads.
     * Shows up to 10 threads, 6 frames each.
     */
    private static String buildBlockedThreadsSummary(ResolvedData data) {
        if (data == null || data.dumps().isEmpty()) return null;
        var snapshot = data.dumps().get(data.dumps().size() - 1);
        var dump = snapshot.parsed();
        if (dump == null) return null;

        var blockedThreads = dump.threads().stream()
            .filter(t -> t.state() == Thread.State.BLOCKED)
            .toList();
        if (blockedThreads.isEmpty()) return null;

        int limit = Math.min(blockedThreads.size(), 10);
        StringBuilder sb = new StringBuilder();
        sb.append(blockedThreads.size()).append(" BLOCKED thread(s)");
        if (blockedThreads.size() > limit) sb.append(" (showing first ").append(limit).append(")");
        sb.append(":\n\n");

        for (int i = 0; i < limit; i++) {
            var t = blockedThreads.get(i);
            ThreadActivityCategorizer.Category activity = ThreadActivityCategorizer.categorize(t);
            sb.append("\"").append(t.name()).append("\"");
            sb.append(" [activity: ").append(activity.getDisplayName()).append("]");
            t.getWaitedOnLock().ifPresent(lock ->
                sb.append(" [waiting for monitor: ").append(lock.className()).append("]"));
            sb.append("\n");
            if (t.stackTrace() != null && !t.stackTrace().isEmpty()) {
                var stack = t.stackTrace().stream().map(Object::toString).toList();
                int shown = Math.min(stack.size(), 6);
                for (int j = 0; j < shown; j++) {
                    sb.append("    at ").append(stack.get(j)).append("\n");
                }
                if (stack.size() > shown)
                    sb.append("    ... ").append(stack.size() - shown).append(" more frames\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** Extract only the data section between the first pair of --- delimiters. */
    private static String extractDataSection(String prompt) {
        int start = prompt.indexOf("---\n");
        if (start < 0) return prompt;
        int end = prompt.indexOf("\n---\n", start + 4);
        if (end < 0) return prompt;
        return prompt.substring(start + 4, end);
    }

    /** Build the response handler, optionally wrapping with a MarkdownRenderer. */
    private java.util.function.Consumer<String> buildResponseHandler(boolean prettyMode, boolean verbose) {
        java.util.function.Consumer<String> raw = content -> {
            System.out.print(content);
            System.out.flush();
        };
        if (prettyMode && System.console() != null) {
            MarkdownRenderer renderer = new MarkdownRenderer(raw);
            return renderer::accept;
        }
        return raw;
    }

    /**
     * Builds the verbose handler for pre-tool reasoning tokens emitted by the model
     * before it decides which tool to call.
     * When showThinking is true: shows dim+italic with a "--- thinking ---" header.
     * When verbose only: shows dim, no header.
     */
    private java.util.function.Consumer<String> buildVerboseToolHandler(boolean verbose, boolean showThinking) {
        if (!showThinking && !verbose) return null;
        boolean isTty = System.console() != null;
        boolean[] headerPrinted = {false};
        return token -> {
            if (!headerPrinted[0]) {
                if (showThinking && isTty) {
                    System.err.print(AnsiCodes.DIM_ON
                        + AnsiCodes.ITALIC_ON
                        + "--- thinking ---\n"
                        + AnsiCodes.RESET);
                }
                headerPrinted[0] = true;
            }
            if (showThinking && isTty) {
                System.err.print(AnsiCodes.DIM_ON
                    + AnsiCodes.ITALIC_ON
                    + token
                    + AnsiCodes.RESET);
            } else {
                System.err.print(AnsiCodes.DIM_ON
                    + token
                    + AnsiCodes.RESET);
            }
            System.err.flush();
        };
    }

    private java.util.function.Consumer<String> buildThinkingHandler(boolean showThinking) {
        if (!showThinking) return null;
        return token -> {
            System.err.print(token);
            System.err.flush();
        };
    }

    /**
     * Creates stream handlers that properly handle &lt;think&gt;...&lt;/think&gt; blocks,
     * including tokens split across chunk boundaries (e.g. "&lt;thi" + "nk&gt;").
     *
     * @param output The buffer to accumulate the final response text
     * @param showThinking Whether to emit thinking tokens to stderr
     * @param suppressOutput If true, don't print to stdout (used for short mode)
     */
    private LlmProvider.StreamHandlers createStreamHandlers(
            StringBuilder output, boolean showThinking, boolean suppressOutput) {

        // Buffer for handling partial tags split across chunks
        StringBuilder tagBuffer = new StringBuilder();
        AtomicBoolean inThinkBlock = new AtomicBoolean(false);

        return new LlmProvider.StreamHandlers(
            content -> {
                // Append to tag buffer and process
                tagBuffer.append(content);
                processTagBuffer(tagBuffer, inThinkBlock, output, showThinking, suppressOutput);
            },
            showThinking && llmProvider.supportsStreaming() ? (thinkingToken -> {
                System.err.print(thinkingToken);
                System.err.flush();
            }) : null
        );
    }

    /**
     * Processes the tag buffer, extracting complete tags and emitting content appropriately.
     * Handles the case where &lt;think&gt; or &lt;/think&gt; tags are split across chunks.
     */
    private void processTagBuffer(StringBuilder buffer, AtomicBoolean inThinkBlock,
                                   StringBuilder output, boolean showThinking, boolean suppressOutput) {
        while (buffer.length() > 0) {
            String text = buffer.toString();

            if (inThinkBlock.get()) {
                // Inside a think block — look for </think>
                int closeIdx = text.indexOf("</think>");
                if (closeIdx >= 0) {
                    // Found close tag
                    String thinking = text.substring(0, closeIdx);
                    if (showThinking && !thinking.isEmpty()) {
                        System.err.print(thinking);
                        System.err.flush();
                    }
                    inThinkBlock.set(false);
                    buffer.delete(0, closeIdx + "</think>".length());
                    continue;
                }
                // Check if buffer might contain a partial </think> at the end
                if (text.length() >= 8 || !couldBePartialTag(text, "</think>")) {
                    // Safe to emit what we have (minus potential partial tag at end)
                    int safeEnd = findSafeEnd(text, "</think>");
                    String safe = text.substring(0, safeEnd);
                    if (showThinking && !safe.isEmpty()) {
                        System.err.print(safe);
                        System.err.flush();
                    }
                    buffer.delete(0, safeEnd);
                }
                return; // Wait for more data
            } else {
                // Outside think block — look for <think>
                int openIdx = text.indexOf("<think>");
                if (openIdx >= 0) {
                    // Emit content before the tag
                    String before = text.substring(0, openIdx);
                    if (!before.isEmpty()) {
                        emitContent(before, output, suppressOutput);
                    }
                    inThinkBlock.set(true);
                    buffer.delete(0, openIdx + "<think>".length());
                    continue;
                }
                // Check if buffer ends with a partial <think> tag
                if (text.length() >= 7 || !couldBePartialTag(text, "<think>")) {
                    int safeEnd = findSafeEnd(text, "<think>");
                    String safe = text.substring(0, safeEnd);
                    if (!safe.isEmpty()) {
                        emitContent(safe, output, suppressOutput);
                    }
                    buffer.delete(0, safeEnd);
                }
                return; // Wait for more data
            }
        }
    }

    private void emitContent(String content, StringBuilder output, boolean suppressOutput) {
        output.append(content);
        if (!suppressOutput) {
            System.out.print(content);
            System.out.flush();
        }
    }

    /**
     * Checks if the end of text could be the start of a partial tag.
     */
    private boolean couldBePartialTag(String text, String tag) {
        for (int len = 1; len < tag.length() && len <= text.length(); len++) {
            if (text.endsWith(tag.substring(0, len))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the index up to which it's safe to emit, avoiding splitting a potential partial tag.
     */
    private int findSafeEnd(String text, String tag) {
        for (int len = tag.length() - 1; len >= 1; len--) {
            if (text.endsWith(tag.substring(0, len))) {
                return text.length() - len;
            }
        }
        return text.length();
    }

    /**
     * Creates a short summary of the analysis by running it through the LLM again.
     */
    private String createShortSummary(String model, String fullAnalysis, boolean isSystemMode, boolean showThinking)
            throws IOException, LlmProvider.LlmException {

        System.err.println("\n--- Creating short summary ---\n");

        String summaryPrompt = getSummaryPrompt(fullAnalysis, isSystemMode);

        List<LlmProvider.Message> messages = new ArrayList<>();
        messages.add(new LlmProvider.Message("system", systemPrompt(showThinking)));
        messages.add(new LlmProvider.Message("user", summaryPrompt));

        StringBuilder summary = new StringBuilder();

        LlmProvider.StreamHandlers handlers = new LlmProvider.StreamHandlers(
            content -> {
                summary.append(content);
                System.out.print(content);
                System.out.flush();
            },
            showThinking && llmProvider.supportsStreaming() ? (thinkingToken -> {
                System.err.print(thinkingToken);
                System.err.flush();
            }) : null
        );

        llmProvider.chat(model, messages, handlers);
        System.out.println(); // Final newline

        return summary.toString();
    }

    private static @NotNull String getSummaryPrompt(String fullAnalysis, boolean isSystemMode) {
        String summaryPrompt;
        if (isSystemMode) {
            summaryPrompt = "Analyze the following system-wide analysis and provide a succinct summary of the current state of the system.\n" +
                            "Focus on the most important findings and issues. Be specific and data-driven.\n" +
                            "Keep it to 3-5 key points maximum. Don't give any advice, just state the state. Don't repeat yourself.\n\n" +
                            "Full Analysis:\n---\n" + fullAnalysis + "\n---";
        } else {
            summaryPrompt = "Analyze the following application analysis and provide a succinct summary of the current state of the application.\n" +
                            "Focus on the most important findings and issues. Be specific and data-driven.\n" +
                            "Keep it to 3-5 key points maximum. Don't give any advice, just state the state. Don't repeat yourself.\n\n" +
                            "Full Analysis:\n---\n" + fullAnalysis + "\n---";
        }
        return summaryPrompt;
    }
}