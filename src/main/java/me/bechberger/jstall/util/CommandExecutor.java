package me.bechberger.jstall.util;

import java.io.IOException;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Utility class to execute system commands and capture their output, either executes locally or remotely via SSH.
 * <p>
 * Supports also creating, reading and accessing temporary files and creating {@link JMXDiagnosticHelper} instances
 */
public abstract class CommandExecutor {

    /**
     * Minimal shell-script template engine: replaces {@code {{KEY}}} placeholders with values.
     * Strips leading indentation (determined by the first non-blank line) so that text blocks
     * can be indented naturally in Java source without producing leading whitespace in the output.
     */
    static String shell(String template, Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) throw new IllegalArgumentException("Expected key/value pairs");
        // Strip shared leading whitespace (text-block style)
        String[] lines = template.split("\n", -1);
        int indent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (!line.isBlank()) {
                int spaces = 0;
                while (spaces < line.length() && line.charAt(spaces) == ' ') spaces++;
                indent = Math.min(indent, spaces);
            }
        }
        if (indent == Integer.MAX_VALUE) indent = 0;
        StringBuilder stripped = new StringBuilder();
        for (String line : lines) {
            stripped.append(line.length() >= indent ? line.substring(indent) : line).append("\n");
        }
        // Trim leading/trailing blank lines
        String result = stripped.toString().stripLeading().stripTrailing();
        // Apply substitutions
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            result = result.replace("{{" + keyValuePairs[i] + "}}", String.valueOf(keyValuePairs[i + 1]));
        }
        return result;
    }

    /**
     * Exception thrown when an SSH command fails, carrying the SSH exit code.
     */
    public static class SSHCommandException extends IOException {
        private final int sshExitCode;

        public SSHCommandException(String message, int sshExitCode) {
            super(message);
            this.sshExitCode = sshExitCode;
        }

        public int getSshExitCode() {
            return sshExitCode;
        }
    }

    /**
     * Temporary file that lives where the command is executed (locally or remotely),
     * with utility methods to read content, copy it locally and delete it.
     */
    public interface TemporaryFile {
        String getPath();
        String readContent() throws IOException;
        void copyInto(Path destination) throws IOException;
        void delete() throws IOException;
    }

    private final boolean remote;
    private final Map<Long, JMXDiagnosticHelper> diagnosticHelpers = new ConcurrentHashMap<>();
    private volatile boolean hasShutdownHook = false;

    CommandExecutor(boolean remote) {
        this.remote = remote;
    }

    private void cleanupDiagnosticHelpers() {
        diagnosticHelpers.values().forEach(JMXDiagnosticHelper::cleanup);
        diagnosticHelpers.clear();
    }

    /**
     * Execute the given command.
     */
    public abstract CommandResult executeCommand(String command, String... args) throws IOException;

    /**
     * Render the command exactly as it would be executed.
     */
    public abstract String describeCommand(String command, String... args);

    public abstract TemporaryFile createTemporaryFile(String prefix, String suffix) throws IOException;

    /**
     * Is this not just a thin shell wrapper.
     */
    public boolean isRemote() {
        return remote;
    }

    /**
     * Provides a JMXDiagnosticHelper for the given PID, which can be used to execute jcmd diagnostic commands and retrieve their output.
     * <p>
     * Importantly: it generates one per PID and caches it, cleaning up resources on shutdown
     */
    public JMXDiagnosticHelper diagnosticHelper(long pid) {
        if (!hasShutdownHook) {
            synchronized (this) {
                if (!hasShutdownHook) {
                    Runtime.getRuntime().addShutdownHook(new Thread(this::cleanupDiagnosticHelpers));
                    hasShutdownHook = true;
                }
            }
        }
        return diagnosticHelpers.computeIfAbsent(pid, p -> {
            try {
                return new JMXDiagnosticHelper(this, p);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create JMXDiagnosticHelper for PID " + p, e);
            }
        });
    }

    /**
     * Evicts the cached JMXDiagnosticHelper for the given PID, cleans it up,
     * and creates a fresh connection. Use after a transient connection failure.
     */
    public JMXDiagnosticHelper reconnectDiagnosticHelper(long pid) {
        JMXDiagnosticHelper old = diagnosticHelpers.remove(pid);
        if (old != null) {
            old.cleanup();
        }
        return diagnosticHelper(pid);
    }

    /**
     * Default implementation of CommandExecutor that executes commands on the local machine using ProcessBuilder.
     */
    public static class LocalCommandExecutor extends CommandExecutor {

        public LocalCommandExecutor() {
            super(false);
        }

        @Override
        public CommandResult executeCommand(String command, String... args) throws IOException {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (args != null) {
                Arrays.stream(args).forEach(a -> pb.command().add(a));
            }
            pb.redirectError(ProcessBuilder.Redirect.PIPE);
            Process process = pb.start();
            OutputCapturingThread outputT = new OutputCapturingThread(process.getInputStream());
            outputT.start();
            OutputCapturingThread errorT = new OutputCapturingThread(process.getErrorStream());
            errorT.start();
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                outputT.interrupt();
                errorT.interrupt();
                try {
                    outputT.join();
                    errorT.join();
                } catch (InterruptedException joinInterrupted) {
                    Thread.currentThread().interrupt();
                }
                Thread.currentThread().interrupt();
                throw new IOException(command + " execution interrupted", e);
            }
            try {
                outputT.join();
                errorT.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(command + " output capture interrupted", e);
            }
            return new CommandResult(outputT.getString(), errorT.getString(), process.exitValue(), process.pid());
        }

        @Override
        public String describeCommand(String command, String... args) {
            if (args == null || args.length == 0) {
                return command;
            }
            return command + " " + String.join(" ", args);
        }

        @Override
        public TemporaryFile createTemporaryFile(String prefix, String suffix) throws IOException {
            var path = Files.createTempFile(prefix, suffix);
            return new TemporaryFile() {
                @Override
                public String getPath() {
                    return path.toString();
                }

                @Override
                public String readContent() throws IOException {
                    return Files.readString(path);
                }

                @Override
                public void delete() throws IOException {
                    Files.deleteIfExists(path);
                }

                @Override
                public void copyInto(Path destination) throws IOException {
                    Files.copy(path, destination);
                }
            };
        }


    }

    /**
     * Execute commands remotely via SSH or other means, also handles temporary files.
     * <p>
     * Has special detection logic for JVM-related commands (jcmd, jps, jstack, jmap, jinfo, jstat, asprof)
     * to resolve their actual path on the remote host before execution.
     */
    public static class RemoteCommandExecutor extends CommandExecutor {
        private static final Set<String> JVM_RELATED_COMMANDS = Set.of("jcmd", "jps", "jstack", "jmap", "jinfo", "jstat", "asprof");
        private final String sshCommandPrefix;
        private final List<String> sshPrefixTokens;
        private final LocalCommandExecutor localExecutor = new LocalCommandExecutor();
        private boolean verbose = false;
        private boolean usePersistentShell = true;
        private PersistentShell persistentShell = null;

        /**
         * Manages a single long-lived SSH shell process.
         * Commands are written to its stdin; sentinel-delimited output is read from stdout.
         * All access is serialized via {@code synchronized} to avoid interleaving.
         */
        private class PersistentShell {
            private final Process process;
            private final OutputStream stdin;
            private final BufferedReader stdout;
            private final String sentinel;
            private boolean dead = false;

            PersistentShell() throws IOException {
                List<String> cmd = new ArrayList<>(sshPrefixTokens);
                // Open an interactive shell — no -c flag, just "cf ssh APP"
                // Remove any trailing "-c" from the prefix (cf ssh APP -c -> cf ssh APP)
                if (!cmd.isEmpty() && cmd.get(cmd.size() - 1).equals("-c")) {
                    cmd = cmd.subList(0, cmd.size() - 1);
                }
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(false);
                process = pb.start();
                stdin  = process.getOutputStream();
                stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                // Use a fixed sentinel; nanoTime() is embedded so it's unique per session
                sentinel = "___JSTALL_PS_" + System.nanoTime() + "___";
                // Bootstrap: set up PATH on the remote shell (single line, no output needed — read until ready sentinel)
                sendLine(JDK_PATH_DISCOVERY_PREFIX + " printf '%s\\n' '" + sentinel + "READY'");
                readUntilSentinel(sentinel + "READY");
                if (verbose) {
                    System.err.println("[verbose] Persistent SSH shell ready: " + cmd);
                }
            }

            /** Send one line to the remote shell stdin. */
            private synchronized void sendLine(String line) throws IOException {
                stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }

            /**
             * Execute a remote command and return its stdout output.
             * Appends a sentinel echo so we know when the command finished.
             */
            synchronized String execute(String remoteCommand) throws IOException {
                if (dead) throw new IOException("Persistent shell is no longer alive");
                String tag = sentinel + System.nanoTime();
                sendLine(remoteCommand + "; printf '%s\\n' '" + tag + "'");
                return readUntilSentinel(tag);
            }

            /**
             * Fire all commands to stdin immediately (pipelined), then collect outputs.
             * Much faster than calling {@link #execute} in a loop because the remote shell
             * can start executing cmd[1] while we're still reading cmd[0]'s output.
             */
            synchronized List<String> executeAll(List<String> remoteCommands) throws IOException {
                if (dead) throw new IOException("Persistent shell is no longer alive");
                // Assign each command a unique tag and fire them all at once
                List<String> tags = new ArrayList<>(remoteCommands.size());
                for (String cmd : remoteCommands) {
                    String tag = sentinel + System.nanoTime() + "_" + tags.size();
                    tags.add(tag);
                    sendLine(cmd + "; printf '%s\\n' '" + tag + "'");
                }
                // Now drain: read each block until its sentinel
                List<String> outputs = new ArrayList<>(remoteCommands.size());
                for (String tag : tags) {
                    outputs.add(readUntilSentinel(tag));
                }
                return outputs;
            }

            private String readUntilSentinel(String tag) throws IOException {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = stdout.readLine()) != null) {
                    if (line.equals(tag)) return sb.toString();
                    sb.append(line).append("\n");
                }
                dead = true;
                throw new IOException("Persistent shell stdout closed unexpectedly (looking for sentinel)");
            }

            synchronized void close() {
                dead = true;
                try { stdin.write("exit\n".getBytes(StandardCharsets.UTF_8)); stdin.flush(); } catch (IOException ignored) {}
                try { process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                process.destroyForcibly();
            }

            boolean isAlive() { return !dead && process.isAlive(); }
        }

        /** Returns the persistent shell, creating it on first call. Returns null if not in persistent mode. */
        private synchronized PersistentShell getOrCreateShell() {
            if (!usePersistentShell) return null;
            if (persistentShell == null || !persistentShell.isAlive()) {
                try {
                    persistentShell = new PersistentShell();
                    // Register close on JVM exit
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        if (persistentShell != null) persistentShell.close();
                    }));
                } catch (IOException e) {
                    if (verbose) System.err.println("[verbose] Failed to open persistent shell, falling back: " + e.getMessage());
                    usePersistentShell = false;
                    return null;
                }
            }
            return persistentShell;
        }

        /**
         * Shell snippet that discovers a JDK/JRE bin directory and prepends it to PATH.
         * Prefers JAVA_HOME if set; otherwise searches for jps (full JDK) or java (JRE-only)
         * under the current directory then the filesystem root.
         * Used when the remote shell is POSIX sh (Linux/Mac).
         */
        // Shell snippet that discovers a JDK/JRE bin directory and prepends it to PATH.
        // Written as a single line (semicolons) so it can be prefixed to any command.
        // Probe order: JAVA_HOME → command -v (instant if on PATH) → find . (CF layout) →
        //              well-known JDK roots → find / (last resort, slow).
        static final String JDK_PATH_DISCOVERY_PREFIX = shell("""
                if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
                  JDK_BIN="$JAVA_HOME/bin";
                else
                  JDK_BIN=$(dirname "$(command -v jps 2>/dev/null)" 2>/dev/null);
                  if [ -z "$JDK_BIN" ] || [ "$JDK_BIN" = "." ]; then
                    JDK_BIN=$(dirname "$(command -v java 2>/dev/null)" 2>/dev/null);
                  fi;
                  if [ -z "$JDK_BIN" ] || [ "$JDK_BIN" = "." ]; then
                    JDK_BIN=$(dirname "$(find . -executable -name jps 2>/dev/null | head -1)" 2>/dev/null);
                  fi;
                  if [ -z "$JDK_BIN" ] || [ "$JDK_BIN" = "." ]; then
                    JDK_BIN=$(dirname "$(find . -executable -name java 2>/dev/null | head -1)" 2>/dev/null);
                  fi;
                  if [ -z "$JDK_BIN" ] || [ "$JDK_BIN" = "." ]; then
                    JDK_BIN=$(dirname "$(find /usr/lib/jvm /usr/java /opt/java /opt/jdk /opt/sapmachine -executable -name jps 2>/dev/null | head -1)" 2>/dev/null);
                  fi;
                  if [ -z "$JDK_BIN" ] || [ "$JDK_BIN" = "." ]; then
                    JDK_BIN=$(dirname "$(find / -executable -name jps 2>/dev/null | head -1)" 2>/dev/null);
                  fi;
                fi;
                if [ -n "$JDK_BIN" ] && [ "$JDK_BIN" != "." ]; then export PATH="$JDK_BIN:$PATH"; fi;
                """)
                // Collapse newlines to semicolons so the whole prefix fits on one line
                // (required: it's prepended to commands that may themselves span one line)
                .replace("\n", " ");

        public RemoteCommandExecutor(String sshCommandPrefix) {
            super(true);
            this.sshCommandPrefix = sshCommandPrefix;
            this.sshPrefixTokens = Arrays.asList(sshCommandPrefix.split("\\s+"));
        }

        public void setVerbose(boolean verbose) {
            this.verbose = verbose;
        }

        public boolean isVerbose() {
            return verbose;
        }

        /** When {@code false}, always use a fresh {@code cf ssh} process per call (old behavior). Default is {@code true}. */
        public void setUsePersistentShell(boolean use) {
            this.usePersistentShell = use;
        }

        public boolean isUsingPersistentShell() {
            return usePersistentShell;
        }


        public String describeCommand(String command, String... args) {
            String actualCommand = JVM_RELATED_COMMANDS.contains(command) ? JDK_PATH_DISCOVERY_PREFIX + command : command;
            if (args == null || args.length == 0) {
                return sshCommandPrefix + " " + actualCommand;
            }
            return sshCommandPrefix + " " + actualCommand + " " + escapeAndJoinArgs(args);
        }

        private CommandResult executeSshCommand(String remotePayload) throws IOException {
            List<String> cmd = new ArrayList<>(sshPrefixTokens);
            cmd.add(remotePayload);
            // On Windows with allowAmbiguousCommands=false, ProcessBuilder calls CreateProcessW directly
            // and won't resolve .cmd/.bat scripts via PATHEXT. Resolve the executable ourselves; if it
            // turns out to be a script, invoke it via "cmd.exe /c call <absolute-path> <args>".
            // We use "call" rather than just "/c <path>" because cmd.exe /c strips the outer quotes
            // from the remaining command line when the first token is quoted, which would break arg
            // passing. "call" avoids that parsing quirk.
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                Path resolved = resolveExecutableOnWindows(cmd.get(0));
                if (resolved != null) {
                    String lower = resolved.toString().toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".cmd") || lower.endsWith(".bat")) {
                        cmd.set(0, resolved.toString());
                        cmd.add(0, "call");
                        cmd.add(0, "/c");
                        cmd.add(0, System.getenv().getOrDefault("COMSPEC", "cmd.exe"));
                    } else {
                        cmd.set(0, resolved.toString());
                    }
                }
            }
            return localExecutor.executeCommand(cmd.get(0), cmd.subList(1, cmd.size()).toArray(new String[0]));
        }

        /**
         * Searches PATH (and PATHEXT on Windows) for {@code name}. Returns the absolute path of the
         * first match, or {@code null} if nothing is found.
         */
        private static Path resolveExecutableOnWindows(String name) {
            if (name.contains("/") || name.contains("\\")) {
                return null; // already a path — let the OS handle it
            }
            String pathExt = System.getenv("PATHEXT");
            List<String> extensions = new ArrayList<>();
            if (pathExt != null) {
                for (String ext : pathExt.split(";")) {
                    if (!ext.isBlank()) extensions.add(ext.toLowerCase(Locale.ROOT));
                }
            }
            if (extensions.isEmpty()) {
                extensions = List.of(".exe", ".cmd", ".bat", ".com");
            }
            String pathEnv = System.getenv("PATH");
            if (pathEnv == null) return null;
            for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                Path base = Path.of(dir).resolve(name);
                // Try exact name first (already has extension)
                if (Files.isRegularFile(base)) return base;
                // Try each PATHEXT extension
                for (String ext : extensions) {
                    Path candidate = Path.of(dir).resolve(name + ext);
                    if (Files.isRegularFile(candidate)) return candidate;
                }
            }
            return null;
        }

        @Override
        public CommandResult executeCommand(String command, String... args) throws IOException {
            // JVM-related commands need PATH discovery only when spawning a fresh SSH process.
            // In persistent-shell mode the shell was already bootstrapped with the correct PATH.
            PersistentShell shell = getOrCreateShell();
            if (shell != null) {
                String remotePayload = args != null && args.length > 0
                        ? command + " " + escapeAndJoinArgs(args)
                        : command;
                if (verbose) {
                    System.err.println("[verbose] SSH (persistent) command: " + remotePayload);
                }
                try {
                    String out = shell.execute(remotePayload);
                    return new CommandResult(out, "", 0, -1);
                } catch (IOException e) {
                    if (verbose) System.err.println("[verbose] Persistent shell error, falling back: " + e.getMessage());
                    usePersistentShell = false;
                    persistentShell = null;
                    // fall through to per-command path below
                }
            }

            String actualCommand;
            if (JVM_RELATED_COMMANDS.contains(command)) {
                actualCommand = JDK_PATH_DISCOVERY_PREFIX + command;
            } else {
                actualCommand = command;
            }

            String remotePayload = args != null && args.length > 0
                    ? actualCommand + " " + escapeAndJoinArgs(args)
                    : actualCommand;
            if (verbose) {
                System.err.println("[verbose] SSH command: " + sshCommandPrefix + " " + remotePayload);
            }
            CommandResult result = executeSshCommand(remotePayload);
            if (verbose) {
                System.err.println("[verbose] Exit code: " + result.exitCode());
                if (!result.out().isBlank()) {
                    System.err.println("[verbose] stdout: " + result.out().trim());
                }
                if (!result.err().isBlank()) {
                    System.err.println("[verbose] stderr: " + result.err().trim());
                }
            }
            return result;
        }

        /**
         * A single command entry in a batch execution request.
         *
         * @param command the command name (e.g. "jcmd")
         * @param args    the arguments (e.g. ["12345", "Thread.print"])
         */
        public record BatchEntry(String command, String[] args) {}

        /**
         * Executes multiple commands in a single SSH round-trip.
         * Each command output is separated by a unique sentinel so the results
         * can be split and returned in order. Any command that fails is returned
         * with an empty stdout and a non-zero exit code.
         *
         * <p>Only available on the remote executor; a local fallback that calls
         * {@link #executeCommand} sequentially is provided for convenience.</p>
         *
         * @param entries list of commands to run
         * @return results in the same order as {@code entries}
         */
        public List<CommandResult> executeBatch(List<BatchEntry> entries) throws IOException {
            if (entries.isEmpty()) return List.of();

            PersistentShell shell = getOrCreateShell();
            if (shell != null) {
                if (verbose) {
                    System.err.println("[verbose] SSH batch via persistent shell (" + entries.size() + " commands)");
                }
                try {
                    // Build command strings first so we can decide on execution strategy
                    List<String> cmds = new ArrayList<>(entries.size());
                    for (BatchEntry entry : entries) {
                        StringBuilder cmd = new StringBuilder(entry.command());
                        if (entry.args() != null && entry.args().length > 0) {
                            cmd.append(" ").append(escapeAndJoinArgs(entry.args()));
                        }
                        cmd.append(" 2>&1");
                        cmds.add(cmd.toString());
                    }

                    // nc-pipeline commands produce small, bounded output (attach-socket protocol
                    // caps responses at a few KB). Fire them all at once so the remote shell can
                    // start cmd[N+1] while we're still reading cmd[N]'s output — same latency win
                    // as the one-shot SSH batch but over the already-open persistent connection.
                    // Regular jcmd commands are executed sequentially to avoid the deadlock that
                    // occurs when a large Thread.print fills the 64 KB stdout pipe buffer.
                    boolean allNc = cmds.stream().allMatch(c -> c.contains("| nc "));
                    List<String> outputs;
                    if (allNc) {
                        outputs = shell.executeAll(cmds);
                    } else {
                        outputs = new ArrayList<>(cmds.size());
                        for (String cmd : cmds) {
                            outputs.add(shell.execute(cmd));
                        }
                    }

                    List<CommandResult> results = new ArrayList<>(entries.size());
                    for (String out : outputs) {
                        results.add(new CommandResult(out, "", 0, -1));
                    }
                    return results;
                } catch (IOException e) {
                    if (verbose) System.err.println("[verbose] Persistent shell batch error, falling back: " + e.getMessage());
                    usePersistentShell = false;
                    persistentShell = null;
                    // fall through to one-shot SSH batch below
                }
            }

            // Fallback: one-shot SSH invocation with all commands concatenated
            // Unique sentinel that won't appear in jcmd output
            String sentinel = "___JSTALL_BATCH_SEP_" + System.nanoTime() + "___";

            StringBuilder script = new StringBuilder();
            // Emit the JDK path discovery once at the top
            script.append(JDK_PATH_DISCOVERY_PREFIX);

            for (int i = 0; i < entries.size(); i++) {
                BatchEntry entry = entries.get(i);
                // Print the sentinel with the index before each command's output
                script.append("printf '%s\\n' '").append(sentinel).append(i).append("'; ");

                String cmd = JVM_RELATED_COMMANDS.contains(entry.command()) ? entry.command() : entry.command();
                script.append(cmd);
                if (entry.args() != null && entry.args().length > 0) {
                    script.append(" ").append(escapeAndJoinArgs(entry.args()));
                }
                script.append("; ");
            }
            // Final sentinel to mark end
            script.append("printf '%s\\n' '").append(sentinel).append(entries.size()).append("'");

            if (verbose) {
                System.err.println("[verbose] SSH batch (" + entries.size() + " commands): " + sshCommandPrefix);
            }

            CommandResult raw = executeSshCommand(script.toString());

            // Split by sentinel lines and reconstruct per-command results
            String[] sections = raw.out().split("(?m)^" + java.util.regex.Pattern.quote(sentinel) + "\\d+\\R?");
            // sections[0] is before the first sentinel (empty/JDK path setup output), sections[1..n] are command outputs
            List<CommandResult> results = new ArrayList<>(entries.size());
            for (int i = 0; i < entries.size(); i++) {
                String out = (i + 1 < sections.length) ? sections[i + 1] : "";
                results.add(new CommandResult(out, raw.err(), 0, raw.pid()));
            }
            return results;
        }

        /**
         * Creates a temporary file on the remote host using {@code mktemp} (Unix/Linux/macOS).
         */
        @Override
        public TemporaryFile createTemporaryFile(String prefix, String suffix) throws IOException {
            String mktempArg = (prefix != null ? prefix : "tmp") + "XXXXXX" + (suffix != null ? suffix : "");
            CommandResult result = executeCommand("mktemp", mktempArg);
            if (result.exitCode() != 0) {
                throw new IOException("Failed to create temporary file on remote host: " + result.err());
            }
            final String path = result.out().trim();
            return new TemporaryFile() {
                @Override
                public String getPath() {
                    return path;
                }

                @Override
                public String readContent() throws IOException {
                    CommandResult r = executeCommand("cat", path);
                    if (r.exitCode() != 0) throw new IOException("Failed to read temporary file on remote host: " + r.err());
                    return r.out();
                }

                @Override
                public void delete() throws IOException {
                    CommandResult r = executeCommand("rm", path);
                    if (r.exitCode() != 0) throw new IOException("Failed to delete temporary file on remote host: " + r.err());
                }

                @Override
                public void copyInto(Path destination) throws IOException {
                    CommandResult r = executeCommand("base64", path);
                    if (r.exitCode() != 0) throw new IOException("Failed to read remote file: " + r.err());
                    byte[] bytes = Base64.getDecoder().decode(r.out().replaceAll("\\s", ""));
                    Files.write(destination, bytes);
                }
            };
        }
    }

    /**
     * Dry-run executor that prints the command line instead of running it.
     */
    public static class DryRunCommandExecutor extends CommandExecutor {
        private final CommandExecutor delegate;
        private static final long FAKE_PID = 12345L;

        public DryRunCommandExecutor(CommandExecutor delegate) {
            super(delegate.isRemote());
            this.delegate = delegate;
        }

        @Override
        public CommandResult executeCommand(String command, String... args) {
            System.err.println("[dry-run] " + delegate.describeCommand(command, args));
            if ("jps".equals(command)) {
                return new CommandResult(FAKE_PID + " dry-run\n", "", 0, FAKE_PID);
            }
            return new CommandResult("", "", 0, -1);
        }

        @Override
        public String describeCommand(String command, String... args) {
            return delegate.describeCommand(command, args);
        }

        @Override
        public TemporaryFile createTemporaryFile(String prefix, String suffix) throws IOException {
            return new TemporaryFile() {
                @Override
                public String getPath() {
                    return "<dry-run-temp>";
                }

                @Override
                public String readContent() throws IOException {
                    return "";
                }

                @Override
                public void copyInto(Path destination) throws IOException {
                }

                @Override
                public void delete() throws IOException {
                }
            };
        }
    }

    public static String escapeAndJoinArgs(String[] args) {
        return Arrays.stream(args)
                .map(CommandExecutor::escapeForShell)
                .collect(Collectors.joining(" "));
    }

    public static String escapeForShell(String arg) {
        // Double-quote escaping works on both Unix remote shells and survives Windows
        // ProcessBuilder argument serialisation (which wraps args in "..." internally).
        // Single-quote escaping breaks when the Windows SSH client re-quotes the payload.
        return "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`") + "\"";
    }
}
