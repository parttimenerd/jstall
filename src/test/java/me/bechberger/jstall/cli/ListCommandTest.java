package me.bechberger.jstall.cli;

import me.bechberger.jstall.util.JVMDiscovery;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ListCommand.
 */
class ListCommandTest {

    @Test
    void testListCommandWithoutFilter() throws Exception {
        ListCommand cmd = new ListCommand();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));

        try {
            int exitCode = cmd.call();

            // Should succeed (exit code 0)
            assertEquals(0, exitCode);

            String output = out.toString();
            // Should contain header
            assertTrue(output.contains("JVM") || output.contains("No running JVMs"));

        } finally {
            System.setOut(System.out);
        }
    }

    @Test
    void testListCommandHelp() {
        ListCommand cmd = new ListCommand();
        CommandLine commandLine = new CommandLine(cmd);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("--help");

        assertEquals(0, exitCode);
        String output = out.toString();
        assertTrue(output.contains("list"));
        assertTrue(output.contains("filter"));
    }

    @Test
    void testListOutputFormat() throws Exception {
        // Get actual JVMs to verify output format
        var jvms = JVMDiscovery.listJVMs();

        if (jvms.isEmpty()) {
            // Skip test if no JVMs available
            return;
        }

        ListCommand cmd = new ListCommand();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));

        try {
            cmd.call();

            String output = out.toString();
            // Should contain PID numbers and class names
            assertTrue(output.contains("Total:"));
            assertTrue(output.contains("JVM"));

        } finally {
            System.setOut(System.out);
        }
    }
}