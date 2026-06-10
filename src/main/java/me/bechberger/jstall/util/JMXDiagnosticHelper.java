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
import java.util.List;
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
            // Fall back to jcmd if MBean connection is not available
            List<String> jcmdArgs = new ArrayList<>();
            jcmdArgs.add(String.valueOf(pid));
            jcmdArgs.add(command);
            if (args != null && args.length > 0) {
                Collections.addAll(jcmdArgs, args);
            }
            CommandResult result = executor.executeCommand("jcmd", jcmdArgs.toArray(String[]::new));
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
     * Closes the JMX connection and detaches from the target JVM.
     * <p>
     * Safe to call multiple times and also when using remote commands.
     */
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