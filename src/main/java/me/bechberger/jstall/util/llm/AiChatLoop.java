package me.bechberger.jstall.util.llm;

import me.bechberger.jstall.util.render.AnsiCodes;
import me.bechberger.jstall.util.render.MarkdownRenderer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Interactive chat REPL for the local AI mode.
 *
 * <p>After the initial analysis turn, drops into a loop that:
 * <ul>
 *   <li>Prints a {@code > } prompt (dim ANSI on TTY)</li>
 *   <li>Reads user input (multi-line with trailing {@code \})</li>
 *   <li>Supports builtins: {@code /exit}, {@code /quit}, {@code /help},
 *       {@code /clear}, {@code /save <file>}</li>
 *   <li>Sends user messages to the model via {@link OpenAiLlmProvider#chatWithToolLoop}</li>
 *   <li>Renders responses with {@link MarkdownRenderer} when pretty-mode is on</li>
 * </ul>
 *
 * <p>The conversation list passed in must already contain the system prompt +
 * the first analysis turn (user question + assistant answer). Each subsequent
 * turn appends to that list, preserving full context.
 */
public class AiChatLoop {

    private static final String PROMPT = AnsiCodes.DIM_ON + "> " + AnsiCodes.RESET;
    private static final String PROMPT_PLAIN = "> ";

    private final OpenAiLlmProvider provider;
    private final String model;
    private final List<LlmProvider.Message> conversation;
    private final List<ToolDefinition> tools;
    private final ToolExecutor executor;
    private final boolean prettyMode;
    private final boolean verbose;
    private final boolean showThinking;
    private final boolean isTty;

    public AiChatLoop(OpenAiLlmProvider provider,
                      String model,
                      List<LlmProvider.Message> conversation,
                      List<ToolDefinition> tools,
                      ToolExecutor executor,
                      boolean prettyMode,
                      boolean verbose,
                      boolean showThinking) {
        this.provider = provider;
        this.model = model;
        this.conversation = conversation;
        this.tools = tools;
        this.executor = executor;
        this.prettyMode = prettyMode;
        this.verbose = verbose;
        this.showThinking = showThinking;
        this.isTty = System.console() != null;
    }

    /** Convenience constructor without showThinking (defaults to false). */
    public AiChatLoop(OpenAiLlmProvider provider,
                      String model,
                      List<LlmProvider.Message> conversation,
                      List<ToolDefinition> tools,
                      ToolExecutor executor,
                      boolean prettyMode,
                      boolean verbose) {
        this(provider, model, conversation, tools, executor, prettyMode, verbose, false);
    }

    /**
     * Run the REPL until the user exits or EOF.
     * Reads from {@code System.in}; writes to {@code System.out} / {@code System.err}.
     */
    public void run() {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.out.println();
        System.out.println(AnsiCodes.DIM_ON + "--- Chat mode active. Type /help for commands, /exit to quit. ---" + AnsiCodes.RESET);
        System.out.println();

        while (true) {
            System.out.print(isTty ? PROMPT : PROMPT_PLAIN);
            System.out.flush();

            String line;
            try {
                line = in.readLine();
            } catch (IOException e) {
                break;
            }
            if (line == null) break; // EOF

            // Multi-line continuation with trailing backslash
            StringBuilder inputBuilder = new StringBuilder(line);
            while (inputBuilder.toString().endsWith("\\")) {
                inputBuilder.setLength(inputBuilder.length() - 1);
                inputBuilder.append("\n");
                System.out.print("... ");
                System.out.flush();
                try {
                    String next = in.readLine();
                    if (next == null) break;
                    inputBuilder.append(next);
                } catch (IOException e) {
                    break;
                }
            }

            String input = inputBuilder.toString().trim();
            if (input.isEmpty()) continue;

            if (input.startsWith("/")) {
                if (handleBuiltin(input)) continue;
                else break; // /exit or /quit
            }

            // Regular user message
            sendAndPrint(input);
        }

        System.out.println();
        System.out.println(AnsiCodes.DIM_ON + "--- Chat ended. ---" + AnsiCodes.RESET);
    }

    /**
     * Handle a builtin command.
     * @return true to continue the loop, false to exit
     */
    private boolean handleBuiltin(String input) {
        String[] parts = input.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        return switch (cmd) {
            case "/exit", "/quit" -> false;
            case "/help" -> {
                System.out.println("""
                    Chat commands:
                      /exit, /quit    — exit chat
                      /clear          — clear history (keeps system context)
                      /save <file>    — save transcript to a file
                      /help           — show this help
                    Multi-line: end a line with \\ to continue on the next line.""");
                yield true;
            }
            case "/clear" -> {
                // Keep only the system message (index 0)
                while (conversation.size() > 1) {
                    conversation.remove(conversation.size() - 1);
                }
                System.out.println(AnsiCodes.DIM_ON + "[History cleared]" + AnsiCodes.RESET);
                yield true;
            }
            case "/save" -> {
                String filePath = parts.length > 1 ? parts[1].trim() : "";
                if (filePath.isEmpty()) {
                    System.err.println("Usage: /save <file>");
                } else {
                    saveTranscript(filePath);
                }
                yield true;
            }
            default -> {
                System.err.println("Unknown command: " + cmd + ". Type /help for available commands.");
                yield true;
            }
        };
    }

    private void sendAndPrint(String userMessage) {
        conversation.add(new LlmProvider.Message("user", userMessage));
        if (verbose) System.err.println("[ai] Sending to model...");

        StringBuilder output = new StringBuilder();
        Consumer<String> downstream = token -> {
            output.append(token);
            System.out.print(token);
            System.out.flush();
        };

        Consumer<String> responseHandler = prettyMode && isTty
            ? buildMarkdownHandler(downstream)
            : downstream;

        LlmProvider.StreamHandlers handlers = new LlmProvider.StreamHandlers(responseHandler, null);

        // Pre-tool reasoning tokens: show dim on stderr when showThinking is on
        Consumer<String> verboseHandler = buildVerboseHandler();

        System.out.println();
        try {
            provider.chatWithToolLoop(model, conversation, tools, executor, handlers, 5, true, verboseHandler);
        } catch (Exception e) {
            System.err.println("\n[error] " + e.getMessage());
            // Remove the user message we added (so history is clean)
            if (!conversation.isEmpty()) {
                conversation.remove(conversation.size() - 1);
            }
            return;
        }
        // Flush any buffered markdown at end of response
        if (prettyMode && isTty) flushMarkdown(responseHandler);
        System.out.println();
    }

    /**
     * Builds the verbose handler for pre-tool reasoning tokens.
     * When showThinking is on: renders dim italic to stderr with a header/footer separator.
     * When verbose but not showThinking: renders dim to stderr without decoration.
     * When neither: returns null (no pre-tool output).
     */
    private Consumer<String> buildVerboseHandler() {
        if (!showThinking && !verbose) return null;

        boolean[] headerPrinted = {false};
        return token -> {
            if (!headerPrinted[0]) {
                if (showThinking && isTty) {
                    System.err.print(AnsiCodes.DIM_ON + AnsiCodes.ITALIC_ON
                        + "--- thinking ---\n" + AnsiCodes.RESET);
                }
                headerPrinted[0] = true;
            }
            if (showThinking && isTty) {
                System.err.print(AnsiCodes.DIM_ON + AnsiCodes.ITALIC_ON + token + AnsiCodes.RESET);
            } else {
                System.err.print(AnsiCodes.DIM_ON + token + AnsiCodes.RESET);
            }
            System.err.flush();
        };
    }

    private MarkdownRenderer activeRenderer = null;

    private Consumer<String> buildMarkdownHandler(Consumer<String> downstream) {
        activeRenderer = new MarkdownRenderer(downstream);
        return token -> activeRenderer.accept(token);
    }

    private void flushMarkdown(Consumer<String> responseHandler) {
        if (activeRenderer != null) {
            activeRenderer.flush();
            activeRenderer = null;
        }
    }

    private void saveTranscript(String filePath) {
        try {
            StringBuilder sb = new StringBuilder();
            for (LlmProvider.Message msg : conversation) {
                if (msg.toolCalls() != null || "tool".equals(msg.role())) continue;
                sb.append("## ").append(msg.role().toUpperCase()).append("\n\n");
                if (msg.content() != null) sb.append(msg.content()).append("\n\n");
            }
            Files.writeString(Path.of(filePath), sb.toString(), StandardCharsets.UTF_8);
            System.out.println(AnsiCodes.DIM_ON + "[Saved to " + filePath + "]" + AnsiCodes.RESET);
        } catch (IOException e) {
            System.err.println("[error] Could not save: " + e.getMessage());
        }
    }
}
