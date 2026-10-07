package me.bechberger.jstall.util;

import com.sun.tools.attach.VirtualMachine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for the HotSpot attach-socket (nc-based) fallback in JMXDiagnosticHelper.
 *
 * <p>Unit tests (no OS dependency) cover the static helper methods.
 * Integration tests spin up a child JVM, trigger its attach socket via
 * {@code VirtualMachine.attach()}, then exercise the shell pipeline directly
 * via {@code sh -c "printf ... | nc -w 2 -U /tmp/.java_pid<PID>"}.
 * They are skipped on Windows (no nc / no POSIX Unix sockets).
 *
 * <p>Both Linux ({@code nc} from OpenBSD-netcat or ncat) and macOS (BSD nc)
 * support {@code -U} for Unix-domain sockets and {@code -w} for idle timeout.
 */
class JMXDiagnosticHelperAttachSocketTest {

    // -------------------------------------------------------------------------
    // Shared child JVM for integration tests
    // -------------------------------------------------------------------------

    private static Process childProcess;
    private static long childPid = -1;

    @BeforeAll
    static void startChildJvm() throws Exception {
        if (!isUnixWithNc()) return; // skip setup on unsupported platforms

        String java = ProcessHandle.current().info().command().orElse("java");
        childProcess = new ProcessBuilder(java,
                "-cp", System.getProperty("java.class.path"),
                AttachSocketSleeperApp.class.getName())
                .redirectErrorStream(true)
                .start();

        childPid = readPid(childProcess);
        // Trigger attach socket creation by attaching from this JVM
        try {
            VirtualMachine vm = VirtualMachine.attach(String.valueOf(childPid));
            vm.startLocalManagementAgent();
            vm.detach();
        } catch (Exception e) {
            // Ignore — the socket may already exist or will be created on demand
        }
        // Give the JVM a moment to create /tmp/.java_pid<pid>
        Path socket = Path.of("/tmp/.java_pid" + childPid);
        long deadline = System.currentTimeMillis() + 5_000;
        while (!Files.exists(socket) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
    }

    @AfterAll
    static void stopChildJvm() {
        if (childProcess != null) childProcess.destroyForcibly();
    }

    // -------------------------------------------------------------------------
    // Unit tests for static helper methods — no OS dependency
    // -------------------------------------------------------------------------

    @Test
    void escapeForPrintf_noSpecialChars() {
        assertEquals("hello", JMXDiagnosticHelper.escapeForPrintf("hello"));
    }

    @Test
    void escapeForPrintf_backslash() {
        assertEquals("a\\\\b", JMXDiagnosticHelper.escapeForPrintf("a\\b"));
    }

    @Test
    void escapeForPrintf_singleQuote() {
        // single-quote in a printf literal must be closed, escaped, reopened
        assertEquals("a'\\''b", JMXDiagnosticHelper.escapeForPrintf("a'b"));
    }

    @Test
    void escapeForPrintf_backslashThenQuote() {
        assertEquals("a\\\\'\\''b", JMXDiagnosticHelper.escapeForPrintf("a\\'b"));
    }

    @Test
    void stripAttachReturnCode_successResponse() {
        String response = "0\nhello world\n";
        assertEquals("hello world\n", JMXDiagnosticHelper.stripAttachReturnCode(response));
    }

    @Test
    void stripAttachReturnCode_errorReturnCode() {
        String response = "1\nerror message\n";
        assertEquals("", JMXDiagnosticHelper.stripAttachReturnCode(response));
    }

    @Test
    void stripAttachReturnCode_emptyOrNull() {
        assertEquals("", JMXDiagnosticHelper.stripAttachReturnCode(""));
        assertEquals("", JMXDiagnosticHelper.stripAttachReturnCode(null));
    }

    @Test
    void stripAttachReturnCode_noNewline() {
        assertEquals("", JMXDiagnosticHelper.stripAttachReturnCode("0"));
    }

    @Test
    void stripAttachReturnCode_multiLineBody() {
        String response = "0\nline1\nline2\nline3\n";
        assertEquals("line1\nline2\nline3\n", JMXDiagnosticHelper.stripAttachReturnCode(response));
    }

    @Test
    void buildAttachSocketShellCmd_noArgs() {
        String cmd = JMXDiagnosticHelper.buildAttachSocketShellCmd(12345L, "VM.uptime", null);
        assertTrue(cmd.startsWith("printf '1\\0jcmd\\0VM.uptime\\0\\0\\0'"), "cmd: " + cmd);
        assertTrue(cmd.contains("| nc -w 2 -U "), "cmd: " + cmd);
        assertTrue(cmd.contains("java_pid12345"), "cmd: " + cmd);
    }

    @Test
    void buildAttachSocketShellCmd_withArg() {
        String cmd = JMXDiagnosticHelper.buildAttachSocketShellCmd(99L, "GC.heap_dump",
                new String[]{"/tmp/out.hprof"});
        // arg is space-separated in the command field, not NUL-separated
        assertTrue(cmd.contains("jcmd\\0GC.heap_dump /tmp/out.hprof\\0\\0\\0"), "cmd: " + cmd);
        assertTrue(cmd.contains("| nc -w 2 -U "), "cmd: " + cmd);
        assertTrue(cmd.contains("java_pid99"), "cmd: " + cmd);
    }

    @Test
    void buildAttachSocketShellCmd_argWithSingleQuote() {
        String cmd = JMXDiagnosticHelper.buildAttachSocketShellCmd(1L, "cmd", new String[]{"a'b"});
        assertTrue(cmd.contains("a'\\''b"), "single-quote not escaped in: " + cmd);
    }

    // -------------------------------------------------------------------------
    // Integration tests — require nc with -U support and a POSIX system
    // -------------------------------------------------------------------------

    /**
     * Sends VM.uptime to the child JVM via the attach socket and verifies
     * the response contains a time value.
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @DisabledOnOs(OS.WINDOWS)
    void executeViaAttachSocket_vmUptime() throws Exception {
        assumeTrue(isUnixWithNc(), "nc with -U support not available");
        assumeTrue(childPid > 0, "Child JVM did not start");
        Path socket = findAttachSocket(childPid);
        assumeTrue(socket != null, "Attach socket not present for child PID " + childPid);

        String ncCmd = buildNcCmd(socket, "VM.uptime", null) + " 2>&1";
        String raw = runShell(ncCmd);

        String uptime = JMXDiagnosticHelper.stripAttachReturnCode(raw);
        assertFalse(uptime.isBlank(), "VM.uptime returned blank; raw=" + raw);
        assertTrue(uptime.contains("s"), "Expected time value with 's' in: " + uptime);
    }

    /**
     * Sends Thread.print twice sequentially to the child JVM via the attach socket.
     * Both rounds must return non-blank thread dump output, validating that the JVM
     * handles repeated connections — the basis for collecting multiple dumps on
     * JRE-only containers.
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @DisabledOnOs(OS.WINDOWS)
    void executeViaAttachSocket_threadPrint_multipleRounds() throws Exception {
        assumeTrue(isUnixWithNc(), "nc with -U support not available");
        assumeTrue(childPid > 0, "Child JVM did not start");
        Path socket = findAttachSocket(childPid);
        assumeTrue(socket != null, "Attach socket not present for child PID " + childPid);

        for (int round = 0; round < 2; round++) {
            String ncCmd = buildNcCmd(socket, "Thread.print", null) + " 2>&1";
            String raw = runShell(ncCmd);

            String dump = JMXDiagnosticHelper.stripAttachReturnCode(raw);
            assertFalse(dump.isBlank(),
                    "Thread.print round " + round + " returned blank; raw=" + raw);
            assertTrue(dump.contains("Thread"),
                    "Expected 'Thread' in dump round " + round + ": "
                    + dump.substring(0, Math.min(200, dump.length())));
        }
    }

    /**
     * Exercises the full JMXDiagnosticHelper.executeCommand path via nc by using a
     * fake executor whose jcmd returns "command not found", forcing the switch to
     * attach-socket mode.
     * Only runs on Linux where the attach socket is at the expected /tmp location
     * (on macOS the socket is under $TMPDIR which the production code doesn't use).
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @DisabledOnOs({OS.WINDOWS, OS.MAC})
    void executeCommand_fallsBackToAttachSocket_whenJcmdAbsent() throws Exception {
        assumeTrue(isUnixWithNc(), "nc with -U support not available");
        assumeTrue(childPid > 0, "Child JVM did not start");
        assumeTrue(attachSocketExists(), "Attach socket not present for child PID " + childPid);

        // Fake executor: jcmd always reports "not found"; all other commands run locally.
        // Check command.equals("jcmd") — not flat.contains("jcmd") — so that the nc pipeline
        // (which contains the literal string "jcmd" inside a printf payload) is not intercepted.
        CommandExecutor fakeExecutor = new CommandExecutor(true) {
            @Override
            public CommandResult executeCommand(String command, String... args) throws java.io.IOException {
                if (command.equals("jcmd")) {
                    return new CommandResult("sh: jcmd: command not found", "", 127, -1);
                }
                // Also catch "sh -c jcmd ..." which is the remote-executor wrapping
                if (command.equals("sh") && args != null && args.length >= 2
                        && args[1].startsWith("jcmd ")) {
                    return new CommandResult("sh: jcmd: command not found", "", 127, -1);
                }
                return new CommandExecutor.LocalCommandExecutor().executeCommand(command, args);
            }

            @Override
            public String describeCommand(String command, String... args) { return command; }

            @Override
            public TemporaryFile createTemporaryFile(String prefix, String suffix) {
                throw new UnsupportedOperationException();
            }
        };

        JMXDiagnosticHelper helper = new JMXDiagnosticHelper(fakeExecutor, childPid);
        String uptime = helper.executeCommand("VM.uptime");

        assertFalse(uptime.isBlank(), "VM.uptime via attach socket fallback returned blank");
        assertTrue(uptime.contains("s"), "Expected time value: " + uptime);
    }

    /**
     * When nc is absent, executeCommand must throw an IOException with a clear message
     * rather than silently returning blank output.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void executeCommand_throwsWhenNcAbsent() throws Exception {
        assumeTrue(childPid > 0, "Child JVM did not start");
        // Fake executor: jcmd absent, nc always reports "nc: not found"
        CommandExecutor fakeExecutor = new CommandExecutor(true) {
            @Override
            public CommandResult executeCommand(String command, String... args) {
                // Intercept jcmd invocations (not printf/nc pipelines that mention "jcmd" in payload)
                if (command.equals("jcmd")) {
                    return new CommandResult("sh: jcmd: command not found", "", 127, -1);
                }
                if (command.equals("sh") && args != null && args.length >= 2
                        && args[1].startsWith("jcmd ")) {
                    return new CommandResult("sh: jcmd: command not found", "", 127, -1);
                }
                // Simulate nc not found: nc probe returns "no"
                String flat = command + (args != null ? " " + String.join(" ", args) : "");
                if (flat.contains("nc -h")) {
                    return new CommandResult("no", "", 0, -1);
                }
                // ensureAttachSocket: pretend socket exists already
                return new CommandResult("", "", 0, -1);
            }

            @Override
            public String describeCommand(String command, String... args) { return command; }

            @Override
            public TemporaryFile createTemporaryFile(String prefix, String suffix) {
                throw new UnsupportedOperationException();
            }
        };

        JMXDiagnosticHelper helper = new JMXDiagnosticHelper(fakeExecutor, childPid);
        java.io.IOException ex = assertThrows(java.io.IOException.class,
            () -> helper.executeCommand("VM.uptime"));
        assertTrue(ex.getMessage().contains("nc") || ex.getMessage().contains("netcat"),
            "Error message should mention nc/netcat: " + ex.getMessage());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean isUnixWithNc() {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) return false;
        try {
            Process p = new ProcessBuilder("nc", "-h")
                    .redirectErrorStream(true)
                    .start();
            String help = new String(p.getInputStream().readAllBytes());
            p.waitFor(2, TimeUnit.SECONDS);
            return help.contains("-U") || help.contains("UNIX");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Builds an nc attach-protocol shell command for the given socket path.
     * Same as {@link JMXDiagnosticHelper#buildAttachSocketShellCmd} but accepts
     * an explicit socket path so tests work on both Linux ({@code /tmp}) and macOS ({@code $TMPDIR}).
     */
    private static String buildNcCmd(Path socketPath, String command, String[] args) {
        StringBuilder cmdline = new StringBuilder(JMXDiagnosticHelper.escapeForPrintf(command));
        if (args != null) {
            for (String arg : args) {
                if (arg != null && !arg.isEmpty()) {
                    cmdline.append(' ').append(JMXDiagnosticHelper.escapeForPrintf(arg));
                }
            }
        }
        String payload = "printf '1\\0jcmd\\0" + cmdline + "\\0\\0\\0'";
        return payload + " | nc -w 2 -U " + socketPath;
    }

    /** Find the attach socket for the given PID (handles Linux /tmp and macOS $TMPDIR). */
    private static Path findAttachSocket(long pid) {
        // Linux: /tmp/.java_pid<pid>
        Path linuxSocket = Path.of("/tmp/.java_pid" + pid);
        if (Files.exists(linuxSocket)) return linuxSocket;
        // macOS: $TMPDIR/.java_pid<pid>
        String tmpdir = System.getenv("TMPDIR");
        if (tmpdir != null) {
            Path macSocket = Path.of(tmpdir, ".java_pid" + pid);
            if (Files.exists(macSocket)) return macSocket;
        }
        return null;
    }

    private boolean attachSocketExists() {
        return childPid > 0 && findAttachSocket(childPid) != null;
    }

    private static String runShell(String cmd) throws Exception {
        Process p = new ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor(5, TimeUnit.SECONDS);
        return out;
    }

    private static long readPid(Process child) throws Exception {
        // Use a background thread so we can impose a real deadline on blocking readLine()
        var ref = new long[]{-1};
        var t = new Thread(() -> {
            try (var br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(child.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.matches("\\d+")) {
                        ref[0] = Long.parseLong(line.trim());
                        return;
                    }
                }
            } catch (Exception ignored) {}
        });
        t.setDaemon(true);
        t.start();
        t.join(8_000);
        if (ref[0] <= 0) fail("Child process did not print its PID within 8 seconds");
        return ref[0];
    }

    /** Child JVM: prints PID and sleeps so the parent can attach to it. */
    static class AttachSocketSleeperApp {
        public static void main(String[] args) throws Exception {
            System.out.println(ProcessHandle.current().pid());
            System.out.flush();
            Thread.sleep(30_000);
        }
    }
}
