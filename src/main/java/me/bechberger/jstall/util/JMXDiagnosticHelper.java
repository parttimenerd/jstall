package me.bechberger.jstall.util;

import com.sun.tools.attach.VirtualMachine;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;

/**
 * Helper class for executing diagnostic commands on remote JVM processes via JMX.
 *
 * <p>This class uses the Attach API to connect to a target JVM and execute
 * diagnostic commands through the DiagnosticCommandMBean.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Get thread dump
 * String threadDump = JMXDiagnosticHelper.executeCommand(12345, "threadPrint");
 *
 * // Get heap dump
 * String heapInfo = JMXDiagnosticHelper.executeCommand(12345, "GC.heap_info");
 *
 * // Execute command with arguments
 * String gcRun = JMXDiagnosticHelper.executeCommand(12345, "GC.run");
 * }</pre>
 */
public class JMXDiagnosticHelper implements AutoCloseable {

    private static final String DIAGNOSTIC_COMMAND_MBEAN = "com.sun.management:type=DiagnosticCommand";

    private final VirtualMachine vm;
    private final JMXConnector connector;
    private final MBeanServerConnection mbsc;
    private final ObjectName diagnosticCmd;

    /**
     * Creates a new JMXDiagnosticHelper attached to the specified JVM process.
     *
     * @param pid Process ID of the target JVM
     * @throws IOException if attachment or JMX connection fails
     */
    public JMXDiagnosticHelper(long pid) throws IOException {
        try {
            // Attach to the target VM
            this.vm = VirtualMachine.attach(String.valueOf(pid));

            // Start or get the JMX management agent
            String jmxUrl = vm.startLocalManagementAgent();
            JMXServiceURL url = new JMXServiceURL(jmxUrl);

            // Connect via JMX
            this.connector = JMXConnectorFactory.connect(url);
            this.mbsc = connector.getMBeanServerConnection();

            // Get the DiagnosticCommand MBean
            this.diagnosticCmd = new ObjectName(DIAGNOSTIC_COMMAND_MBEAN);

        } catch (Exception e) {
            // Clean up on failure
            cleanup();
            throw new IOException("Failed to attach to JVM process " + pid + ": " + e.getMessage(), e);
        }
    }

    /**
     * Executes a diagnostic command without arguments.
     *
     * @param command The diagnostic command to execute (e.g., "threadPrint", "GC.heap_info")
     * @return The command output as a String
     * @throws IOException if the command execution fails
     */
    public String executeCommand(String command) throws IOException {
        return executeCommand(command, (String[]) null);
    }

    /**
     * Executes a diagnostic command with arguments.
     *
     * @param command The diagnostic command to execute
     * @param args Optional arguments for the command
     * @return The command output as a String
     * @throws IOException if the command execution fails
     */
    public String executeCommand(String command, String... args) throws IOException {
        try {
            Object[] params = new Object[] { args };
            String[] signature = new String[] { "[Ljava.lang.String;" };

            Object result = mbsc.invoke(diagnosticCmd, command, params, signature);

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
     * Gets a thread dump from the target JVM.
     * Equivalent to executing "Thread.print" via jcmd.
     *
     * @return Thread dump as a String
     * @throws IOException if the operation fails
     */
    public String getThreadDump() throws IOException {
        return executeCommand("threadPrint");
    }

    /**
     * Gets heap information from the target JVM.
     *
     * @return Heap information as a String
     * @throws IOException if the operation fails
     */
    public String getHeapInfo() throws IOException {
        return executeCommand("GC.heap_info");
    }

    /**
     * Triggers a garbage collection in the target JVM.
     *
     * @return GC execution result as a String
     * @throws IOException if the operation fails
     */
    public String runGC() throws IOException {
        return executeCommand("GC.run");
    }

    /**
     * Gets the VM version information.
     *
     * @return VM version as a String
     * @throws IOException if the operation fails
     */
    public String getVMVersion() throws IOException {
        return executeCommand("VM.version");
    }

    /**
     * Gets VM command line flags.
     *
     * @return VM flags as a String
     * @throws IOException if the operation fails
     */
    public String getVMFlags() throws IOException {
        return executeCommand("VM.flags");
    }

    @Override
    public void close() throws IOException {
        cleanup();
    }

    private void cleanup() {
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

    /**
     * Static convenience method to execute a diagnostic command without managing the connection.
     * Creates a connection, executes the command, and closes the connection.
     *
     * @param pid Process ID of the target JVM
     * @param command The diagnostic command to execute
     * @param args Optional arguments for the command
     * @return The command output as a String
     * @throws IOException if attachment, execution, or cleanup fails
     */
    public static String executeCommand(long pid, String command, String... args) throws IOException {
        try (JMXDiagnosticHelper helper = new JMXDiagnosticHelper(pid)) {
            return helper.executeCommand(command, args);
        }
    }
}