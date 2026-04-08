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
    private final VirtualMachine vm;
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
        try {
            // Attach to the target VM
            this.vm = VirtualMachine.attach(String.valueOf(pid));

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
            }

        } catch (Exception e) {
            // Clean up on failure
            cleanup();
            throw new IOException("Failed to attach to JVM process " + pid + ": " + e.getMessage(), e);
        }
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
            return executor.executeCommand("jcmd", jcmdArgs.toArray(String[]::new)).out();
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