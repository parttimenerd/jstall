package me.bechberger.jstall.cli;

import me.bechberger.jstall.util.JVMDiscovery;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

        } finally {
            System.setOut(System.out);
        }
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
            assertThat(output).containsPattern("\\d+"); // PID
        } finally {
            System.setOut(System.out);
        }
    }
}