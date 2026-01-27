package me.bechberger.jstall.cli;

import me.bechberger.jstall.util.JVMDiscovery;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for ListCommand.
 */
class ListCommandTest {

    @Test
    void testListCommandWithoutFilter() {
        var result = Util.run("list");

        // Should succeed (exit code 0)
        assertEquals(0, result.exitCode());
    }

    @Test
    void testListOutputFormat() throws IOException {
        // Get actual JVMs to verify output format
        var jvms = JVMDiscovery.listJVMs();

        if (jvms.isEmpty()) {
            // Skip test if no JVMs available
            return;
        }

        var result = Util.run("list");

        // Should succeed
        assertEquals(0, result.exitCode());

        String output = result.out();
        // Should contain PID numbers and class names
        assertThat(output).containsPattern("\\d+"); // PID
    }
}