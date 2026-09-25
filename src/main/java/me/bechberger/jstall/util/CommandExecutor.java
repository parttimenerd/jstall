package me.bechberger.jstall.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Utility class to execute system commands and capture their output, either executes locally or remotely via SSH.
 * <p>
 * Supports also creating, reading and accessing temporary files and creating {@link JMXDiagnosticHelper} instances
 */
public abstract class CommandExecutor {

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

        /**
         * Shell snippet that discovers a JDK bin directory and prepends it to PATH.
         * Prefers JAVA_HOME if set, then searches from . and finally from /.
         * Used when the remote shell is POSIX sh (Linux/Mac).
         */
        private static final String JDK_PATH_DISCOVERY_PREFIX =
                "if [ -n \"$JAVA_HOME\" ] && [ -x \"$JAVA_HOME/bin/jps\" ]; then JDK_BIN=\"$JAVA_HOME/bin\"; " +
                "else " +
                    "JDK_BIN=$(dirname \"$(find . -executable -name jps 2>/dev/null | head -1)\" 2>/dev/null); " +
                    "if [ -z \"$JDK_BIN\" ] || [ \"$JDK_BIN\" = \".\" ]; then " +
                        "JDK_BIN=$(dirname \"$(find / -executable -name jps 2>/dev/null | head -1)\" 2>/dev/null); " +
                    "fi; " +
                "fi; " +
                "if [ -n \"$JDK_BIN\" ] && [ \"$JDK_BIN\" != \".\" ]; then export PATH=\"$JDK_BIN:$PATH\"; fi; ";

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

        @Override
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
