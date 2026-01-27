package me.bechberger.jstall.util;

import com.sun.tools.attach.VirtualMachine;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.util.Arrays;

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
public class JMXDiagnosticHelper implements AutoCloseable {

    private static final String DIAGNOSTIC_COMMAND_MBEAN = "com.sun.management:type=DiagnosticCommand";

    private final VirtualMachine vm;
    private boolean noMBeanConnection;
    private JMXConnector connector;
    private MBeanServerConnection mbsc;
    private ObjectName diagnosticCmd;

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

    public String executeCommand(String mbeanCommand, String jcmdCommand) throws IOException {
        return executeCommand(mbeanCommand, jcmdCommand, (String[]) null);
    }

    public String executeCommand(String mbeanCommand, String jcmdCommand, String... args) throws IOException {
        if (noMBeanConnection) {
            return executeCommandNoMBean(jcmdCommand, args);
        }
        return executeCommandMBean(mbeanCommand, args);
    }

    private String executeCommandMBean(String command, String... args) throws IOException {
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

    /** Call jcmd as fallback if MBean connection is not available */
    private String executeCommandNoMBean(String command, String... args) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("jcmd", vm.id(), command);
        if (args != null) {
            Arrays.stream(args).forEach(a -> pb.command().add(a));
        }
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        Process process = pb.start();
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            throw  new IOException("jcmd execution interrupted", e);
        }
        String error = new String(process.getErrorStream().readAllBytes());
        String output = new String(process.getInputStream().readAllBytes());
        if (process.exitValue() != 0) {
            throw new IOException("jcmd failed: " + error);
        }
        return output;
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

    public static String getThreadDump(long pid) throws IOException {
        try (JMXDiagnosticHelper helper = new JMXDiagnosticHelper(pid)) {
            return helper.getThreadDump();
        }
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
     * @param args Optional arguments for the command
     * @return The command output as a String
     * @throws IOException if attachment, execution, or cleanup fails
     */
    public static String executeCommand(long pid, String mbeanCommand, String jcmdCommand, String... args) throws IOException {
        try (JMXDiagnosticHelper helper = new JMXDiagnosticHelper(pid)) {
            return helper.executeCommand(mbeanCommand, jcmdCommand, args);
        }
    }
}