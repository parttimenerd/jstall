package me.bechberger.jstall.util;

import com.sun.tools.attach.VirtualMachine;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Helper class for executing diagnostic commands on remote JVM processes via JMX.
 *
 * <p>This class uses the Attach API to connect to a target JVM and execute
 * diagnostic commands through the DiagnosticCommandMBean.
 * <p>If it fails, it tries to fall back to jcmd
 *
 * <p>Example usage:
 * <pre>{@code
 * // Get thread dump
 * String threadDump = JMXDiagnosticHelper.executeCommand(12345, "Thread.print");
 *
 * // Get heap dump
 * String heapInfo = JMXDiagnosticHelper.executeCommand(12345, "GC.heap_info");
 *
 * // Execute command with arguments
 * String gcRun = JMXDiagnosticHelper.executeCommand(12345, "GC.run");
 * }</pre>
 */
public class JMXDiagnosticHelper {

    private static final String DIAGNOSTIC_COMMAND_MBEAN = "com.sun.management:type=DiagnosticCommand";

    private final long pid;
    private VirtualMachine vm;
    private boolean noMBeanConnection;
    private JMXConnector connector;
    private MBeanServerConnection mbsc;
    private ObjectName diagnosticCmd;

    private final CommandExecutor executor;

    /**
     * Whether we've determined that jcmd is absent and the attach socket should be used instead.
     * Null = unknown (first command will probe), true = use attach socket, false = use jcmd.
     */
    private Boolean useAttachSocket = null;

    /**
     * Whether nc with -U (Unix-domain socket) support is available on the target system.
     * Null = unknown (checked on first nc attempt), true/false after the probe.
     */
    private Boolean ncAvailable = null;

    /**
     * Cache populated by {@link #prefetchJcmd}: maps "COMMAND[ ARG...]" keys to their output.
     * Consumed (removed) by {@link #executeCommand} on the first hit so memory doesn't grow.
     */
    private final Map<String, String> prefetchCache = new ConcurrentHashMap<>();

    /**
     * Creates a new JMXDiagnosticHelper attached to the specified JVM process.
     * <p>
     * Create new instances via {@link CommandExecutor#diagnosticHelper(long)} to ensure proper caching and resource management.
     *
     * @param pid Process ID of the target JVM
     * @throws IOException if attachment or JMX connection fails
     */
    JMXDiagnosticHelper(CommandExecutor executor, long pid) throws IOException {
        this.pid = pid;
        this.executor = executor;
        if (executor.isRemote()) {
            this.noMBeanConnection = true;
            this.vm = null;
            return;
        }
        // Skip JMX attach if the target JVM runs on a different major version.
        // VirtualMachine.attach() uses a version-specific protocol; cross-major-version
        // attach (e.g. GraalVM 25 → SAP JDK 21) hangs indefinitely waiting for a socket
        // that never appears. jcmd is a separate binary that handles this transparently.
        if (!isSameMajorVersion(pid)) {
            this.noMBeanConnection = true;
            this.vm = null;
            return;
        }
        // VirtualMachine.attach() can still hang on some JVMs even within the same version.
        // Run it on a background thread with a 5-second timeout; fall back to jcmd on timeout.
        ExecutorService attachEx = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "jmx-attach-" + pid);
            t.setDaemon(true);
            return t;
        });
        VirtualMachine attached = null;
        try {
            Future<VirtualMachine> future = attachEx.submit(() -> VirtualMachine.attach(String.valueOf(pid)));
            try {
                attached = future.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                // Don't cancel — the native attach thread holds the socket; let it finish
                // naturally in the background so subsequent jcmd calls aren't blocked.
                this.noMBeanConnection = true;
                this.vm = null;
                return;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                cleanup();
                throw new IOException("Failed to attach to JVM process " + pid + ": " + cause.getMessage(), cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cleanup();
                throw new IOException("Interrupted while attaching to JVM process " + pid, e);
            }
        } finally {
            attachEx.shutdown(); // don't shutdownNow — let the attach thread finish cleanly
        }
        this.vm = attached;
        try {
            // Start or get the JMX management agent
            String jmxUrl = vm.startLocalManagementAgent();
            JMXServiceURL url = new JMXServiceURL(jmxUrl);

            // Connect via JMX
            this.connector = JMXConnectorFactory.connect(url);
            this.mbsc = connector.getMBeanServerConnection();

            // Get the DiagnosticCommand MBean
            this.diagnosticCmd = new ObjectName(DIAGNOSTIC_COMMAND_MBEAN);
            this.noMBeanConnection = false;
        } catch (IOException e) {
            this.noMBeanConnection = true;
        } catch (Exception e) {
            cleanup();
            throw new IOException("Failed to attach to JVM process " + pid + ": " + e.getMessage(), e);
        }
    }

    /** Returns true if the target JVM's major version matches this JVM's major version. */
    private static boolean isSameMajorVersion(long pid) {
        int myMajor = Runtime.version().feature();
        try {
            // jcmd VM.version is fast (<100ms) and works cross-version
            Process p = new ProcessBuilder("jcmd", String.valueOf(pid), "VM.version")
                .redirectErrorStream(true)
                .start();
            String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            p.waitFor(3, TimeUnit.SECONDS);
            // Output contains e.g. "JDK 21.0.0" or "OpenJDK ... version 21+35"
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:version|JDK)\\s+(\\d+)[.+]")
                .matcher(output);
            if (m.find()) {
                int targetMajor = Integer.parseInt(m.group(1));
                return targetMajor == myMajor;
            }
        } catch (Exception ignored) {
        }
        return false; // unknown — skip attach to be safe
    }

    public String executeCommand(String command, String... args) throws IOException {
        if (noMBeanConnection) {
            // Check the prefetch cache first (populated by DataCollector for remote batch runs)
            String cacheKey = makeCacheKey(command, args);
            String cached = prefetchCache.remove(cacheKey);
            if (cached != null) {
                return cached;
            }

            // For remote execution: try jcmd first; if absent (JRE-only container), use the
            // HotSpot attach socket via nc (works without any JDK tools installed).
            if (Boolean.TRUE.equals(useAttachSocket)) {
                return executeViaAttachSocket(command, args);
            }

            // Fall back to jcmd if MBean connection is not available.
            List<String> jcmdArgs = new ArrayList<>();
            jcmdArgs.add(String.valueOf(pid));
            jcmdArgs.add(command);
            if (args != null && args.length > 0) {
                Collections.addAll(jcmdArgs, args);
            }
            CommandResult result;
            if (executor instanceof CommandExecutor.RemoteCommandExecutor) {
                // Wrap in sh -c "... 2>&1" so that "jcmd: not found" errors appear in stdout
                // (persistent shell merges only stdout; stderr would be lost otherwise).
                String jcmdLine = "jcmd " + String.join(" ", jcmdArgs);
                result = executor.executeCommand("sh", "-c", jcmdLine + " 2>&1");
            } else {
                result = executor.executeCommand("jcmd", jcmdArgs.toArray(String[]::new));
            }
            String combined = result.out() + result.err();
            // Detect jcmd-not-found: switch permanently to attach socket mode for this session
            if (combined.contains("not found") || combined.contains("No such file")) {
                useAttachSocket = true;
                ensureAttachSocket();
                return executeViaAttachSocket(command, args);
            }
            useAttachSocket = false;
            if (executor.isRemote() && result.exitCode() != 0 && result.out().isBlank()) {
                String detail = result.err().isBlank() ? "(no output)" : result.err().trim();
                throw new CommandExecutor.SSHCommandException(
                    "Remote jcmd command failed (exit " + result.exitCode() + "): " + detail,
                    result.exitCode());
            }
            return result.out();
        }
        try {
            Object[] params = new Object[] { args };
            String[] signature = new String[] { "[Ljava.lang.String;" };

            Object result = mbsc.invoke(diagnosticCmd, transformJcmdToMBeanName(command), params, signature);

            if (result instanceof String) {
                return (String) result;
            } else {
                throw new IOException("Unexpected result type from " + command + ": " +
                                      (result != null ? result.getClass().getName() : "null"));
            }
        } catch (Exception e) {
            throw new IOException("Failed to execute diagnostic command '" + command + "': " + e.getMessage(), e);
        }
    }

    /**
     * Transform the original jcmd command name into the JMX operation name
     * using the same rules as DiagnosticCommandImpl:
     * - lowercase the entire first segment (before the first '.' or '_')
     * - remove '.' and '_' and uppercase the character following each separator
     * Examples:
     *  "VM.system_properties" -> "vmSystemProperties"
     *  "GC.heap_dump" -> "gcHeapDump"
     */
    private static String transformJcmdToMBeanName(String cmd) {
        StringBuilder out = new StringBuilder();
        boolean inFirstSegment = true;
        boolean capitalizeNext = false;

        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (c == '.' || c == '_') {
                // separators are removed and next character is capitalized
                inFirstSegment = false;
                capitalizeNext = true;
                continue;
            }

            if (capitalizeNext) {
                out.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else if (inFirstSegment) {
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }

        return out.toString();
    }

    /**
     * Gets a thread dump from the target JVM.
     * Equivalent to executing "Thread.print" via jcmd.
     *
     * @return Thread dump as a String
     * @throws IOException if the operation fails
     */
    public String getThreadDump() throws IOException {
        return executeCommand("threadPrint", "Thread.print");
    }

    /**
     * Gets the output of {@code VM.system_properties} from the target JVM.
     * Equivalent to executing {@code "VM.system_properties"} via jcmd.
     */
    public String getSystemProperties() throws IOException {
        return executeCommand("VM.system_properties");
    }

    public long pid() {
        return pid;
    }

    /**
     * Returns the {@link CommandExecutor} associated with this helper.
     * Requirements can use this to run arbitrary system commands on the same
     * host (or remote machine) as the target JVM.
     */
    public CommandExecutor getExecutor() {
        return executor;
    }

    /**
     * Prefetches the output of multiple jcmd commands in a single SSH round-trip
     * (remote only; silently ignored when running locally via JMX).
     * Results are cached and consumed by the next matching {@link #executeCommand} call.
     *
     * @param commands list of (jcmd command name, optional args) pairs to prefetch
     */
    public void prefetchJcmd(List<Map.Entry<String, String[]>> commands) throws IOException {
        if (!noMBeanConnection || commands.isEmpty()) return;
        if (!(executor instanceof CommandExecutor.RemoteCommandExecutor remote)) return;

        // If we already know jcmd is absent, use the attach socket batch path
        if (Boolean.TRUE.equals(useAttachSocket)) {
            ensureAttachSocket();
            prefetchViaAttachSocket(commands);
            return;
        }

        List<CommandExecutor.RemoteCommandExecutor.BatchEntry> entries = new ArrayList<>();
        for (Map.Entry<String, String[]> cmd : commands) {
            List<String> jcmdArgs = new ArrayList<>();
            jcmdArgs.add(String.valueOf(pid));
            jcmdArgs.add(cmd.getKey());
            if (cmd.getValue() != null) {
                Collections.addAll(jcmdArgs, cmd.getValue());
            }
            entries.add(new CommandExecutor.RemoteCommandExecutor.BatchEntry("jcmd", jcmdArgs.toArray(String[]::new)));
        }

        List<CommandResult> results = remote.executeBatch(entries);
        boolean jcmdAbsent = false;
        for (int i = 0; i < commands.size(); i++) {
            String out = results.get(i).out();
            if (out.contains("not found") || out.contains("No such file")) {
                jcmdAbsent = true;
                break;
            }
        }
        if (jcmdAbsent) {
            // jcmd absent — switch to attach socket mode and redo prefetch
            useAttachSocket = true;
            ensureAttachSocket();
            prefetchViaAttachSocket(commands);
            return;
        }
        useAttachSocket = false;
        for (int i = 0; i < commands.size(); i++) {
            Map.Entry<String, String[]> cmd = commands.get(i);
            prefetchCache.put(makeCacheKey(cmd.getKey(), cmd.getValue()), results.get(i).out());
        }
    }

    /**
     * Prefetches multiple commands via the attach socket, batching all nc calls into a single
     * SSH round-trip using the persistent shell's pipelining.
     */
    private void prefetchViaAttachSocket(List<Map.Entry<String, String[]>> commands) throws IOException {
        if (!(executor instanceof CommandExecutor.RemoteCommandExecutor remote)) return;

        // Build each command as a complete shell pipeline. We pass the entire pipeline as
        // the BatchEntry command (no args), bypassing escapeAndJoinArgs which would corrupt
        // the null bytes inside the printf literal.
        List<CommandExecutor.RemoteCommandExecutor.BatchEntry> entries = new ArrayList<>(commands.size());
        for (Map.Entry<String, String[]> cmd : commands) {
            String shellCmd = buildAttachSocketShellCmd(pid, cmd.getKey(), cmd.getValue());
            entries.add(new CommandExecutor.RemoteCommandExecutor.BatchEntry(shellCmd, null));
        }

        List<CommandResult> results = remote.executeBatch(entries);
        for (int i = 0; i < commands.size(); i++) {
            Map.Entry<String, String[]> cmd = commands.get(i);
            String body = stripAttachReturnCode(results.get(i).out());
            prefetchCache.put(makeCacheKey(cmd.getKey(), cmd.getValue()), body);
        }
    }

    /**
     * Builds the full shell pipeline that sends one attach-protocol command via nc.
     *
     * <p>Protocol (JDK 9+): {@code "1\0jcmd\0<command>\0<arg1>\0<arg2>\0"}.
     * The operation name is always {@code "jcmd"}; the actual jcmd command
     * ({@code Thread.print}, {@code VM.uptime}, etc.) is passed as the first argument.
     * This format works on JDK 9–25 and both Linux and macOS.
     */
    static String buildAttachSocketShellCmd(long targetPid, String command, String[] args) {
        String arg1 = (args != null && args.length > 0) ? args[0] : "";
        String arg2 = (args != null && args.length > 1) ? args[1] : "";
        String payload = "printf '1\\0jcmd\\0" + escapeForPrintf(command)
                + "\\0" + escapeForPrintf(arg1)
                + "\\0" + escapeForPrintf(arg2) + "\\0'";
        return payload + " | nc -w 2 -U /tmp/.java_pid" + targetPid;
    }

    /** Strips the numeric return-code first line from an attach-protocol response. Returns "" on failure. */
    static String stripAttachReturnCode(String out) {
        if (out == null || out.isEmpty()) return "";
        int nl = out.indexOf('\n');
        if (nl < 0) return "";
        return "0".equals(out.substring(0, nl).trim()) ? out.substring(nl + 1) : "";
    }



    private String makeCacheKey(String command, String[] args) {
        if (args == null || args.length == 0) return command;
        return command + " " + String.join(" ", args);
    }

    /**
     * Ensures the HotSpot attach socket {@code /tmp/.java_pid<PID>} exists.
     * If absent, triggers socket creation via the standard attach-handshake: write
     * {@code .attach_pid<PID>} into the process's cwd and send SIGQUIT, then poll
     * up to 5 seconds for the socket to appear.
     * <p>
     * Also probes whether {@code nc -U} (Unix-domain socket support) is available and
     * sets {@link #ncAvailable} accordingly. Throws {@link IOException} if nc is absent,
     * since the attach-socket path cannot work without it.
     */
    private void ensureAttachSocket() throws IOException {
        // Probe nc -U availability if not yet known
        if (ncAvailable == null) {
            CommandResult ncProbe = executor.executeCommand("sh", "-c", "nc -h 2>&1 | grep -q '\\-U' && echo yes || echo no");
            ncAvailable = "yes".equals(ncProbe.out().trim());
        }
        if (!ncAvailable) {
            throw new IOException(
                "JRE-only container detected (jcmd absent) but nc with -U (Unix-domain socket) support is not available. " +
                "Install netcat (e.g. 'apt-get install netcat-openbsd' or 'yum install nmap-ncat') to enable attach-socket diagnostics.");
        }

        String socketPath = "/tmp/.java_pid" + pid;
        // Trigger attach-socket creation if absent, then poll up to 5 s.
        // Sending SIGQUIT to the JVM causes it to create the attach socket and print a thread dump to stderr;
        // the .attach_pid<PID> sentinel tells the JVM's Signal Dispatcher to create the socket on the next signal.
        executor.executeCommand("sh", "-c",
            "test -S " + socketPath + " && exit 0; " +
            "CWD=$(readlink /proc/" + pid + "/cwd 2>/dev/null || echo /tmp); " +
            "touch \"$CWD/.attach_pid" + pid + "\" 2>/dev/null; " +
            "kill -QUIT " + pid + " 2>/dev/null; " +
            "for i in 1 2 3 4 5 6 7 8 9 10; do sleep 0.5; test -S " + socketPath + " && exit 0; done; " +
            "exit 1");
    }

    /**
     * Sends a single diagnostic command to the JVM via the HotSpot attach socket.
     *
     * <p>Protocol: write {@code "1\0jcmd\0cmd\0arg1\0arg2\0"} to the Unix-domain socket;
     * first response line is a numeric return code (0 = success), rest is the output.
     * {@code nc -w 2} provides a safety-net timeout in case the socket is slow to close.
     * The full pipeline is sent as a single command string to avoid shell-escaping corruption
     * of the {@code \0} null bytes inside the printf literal.
     * <p>
     * If the nc command produces no output at all (socket not yet ready or nc not available),
     * retries once after re-triggering attach-socket creation.
     */
    private String executeViaAttachSocket(String command, String... args) throws IOException {
        // Pass the full pipeline as the command (no args) so it reaches the remote shell verbatim,
        // bypassing escapeAndJoinArgs which would corrupt \0 inside the printf literal.
        String shellCmd = buildAttachSocketShellCmd(pid, command, args) + " 2>&1";
        CommandResult result = executor.executeCommand(shellCmd);
        String body = stripAttachReturnCode(result.out());
        if (!body.isBlank()) {
            return body;
        }
        // Retry: socket may have been slow to appear; re-trigger and wait
        ensureAttachSocket();
        result = executor.executeCommand(shellCmd);
        body = stripAttachReturnCode(result.out());
        if (body.isBlank() && !result.out().isBlank()) {
            // nc ran but the JVM returned a non-zero status code — log the raw response
            throw new IOException("Attach-socket command '" + command + "' failed: " + result.out().trim());
        }
        return body;
    }

    static String escapeForPrintf(String s) {
        return s.replace("\\", "\\\\").replace("'", "'\\''");
    }


    public void cleanup() {
        if (noMBeanConnection) {
            return;
        }
        try {
            if (connector != null) {
                connector.close();
            }
        } catch (Exception e) {
            // Ignore
        }

        try {
            if (vm != null) {
                vm.detach();
            }
        } catch (Exception e) {
            // Ignore
        }
    }
}