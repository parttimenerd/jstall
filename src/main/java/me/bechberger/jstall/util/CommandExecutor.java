package me.bechberger.jstall.util;

import me.bechberger.femtocli.RunResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Utility class to execute system commands and capture their output, either executes locally or remotely via SSH.
 * <p>
 * Supports also creating, reading and accessing temporary files and creating {@link JMXDiagnosticHelper} instances
 */
public abstract class CommandExecutor {

    public interface TemporaryFile {
        String getPath();
        String readContent() throws IOException;
        void copyInto(Path destination) throws IOException;
        void delete() throws IOException;
    }

    private final boolean remote;
    private final Map<Long, JMXDiagnosticHelper> diagnosticHelpers = new HashMap<>();
    private boolean hasShutdownHook = false;

    CommandExecutor(boolean remote) {
        this.remote = remote;
    }

    private void cleanupDiagnosticHelpers() {
        diagnosticHelpers.values().forEach(JMXDiagnosticHelper::cleanup);
        diagnosticHelpers.clear();
    }

    public abstract CommandResult executeCommand(String command, String... args) throws IOException;

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
            Runtime.getRuntime().addShutdownHook(new Thread(this::cleanupDiagnosticHelpers));
            hasShutdownHook = true;
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
     * Default implementation of CommandExecutor that executes commands on the local machine using ProcessBuilder.
     */
    public static class LocalCommandExecutor extends CommandExecutor {

        public LocalCommandExecutor() {
            super(false);
        }

        @Override
        public CommandResult executeCommand(String command, String[] args) throws IOException {
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
                throw new IOException(command + " execution interrupted", e);
            }
            return new CommandResult(outputT.getString(), errorT.getString(), process.exitValue(), process.pid());
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
     */
    public static class RemoteCommandExecutor extends CommandExecutor {
        private final String sshCommandPrefix;
        private final LocalCommandExecutor localExecutor = new LocalCommandExecutor();

        public RemoteCommandExecutor(String sshCommandPrefix) {
            super(true);
            this.sshCommandPrefix = sshCommandPrefix;
        }

        @Override
        public CommandResult executeCommand(String command, String... args) throws IOException {
            String fullCommand = sshCommandPrefix + " ";
            if (args != null) {
                fullCommand += '"' + command + " " + escapeAndJoinArgs(args) + '"';
            } else {
                fullCommand += '"' + command + '"';
            }
            return localExecutor.executeCommand("sh", new String[]{"-c", fullCommand});
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

    public static String escapeAndJoinArgs(String[] args) {
        return Arrays.stream(args)
                .map(CommandExecutor::escapeForShell)
                .collect(Collectors.joining(" "));
    }

    public static String escapeForShell(String arg) {
        // Simple escaping for shell arguments
        return "'" + arg.replace("'", "'\\''") + "'";
    }
}