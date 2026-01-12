package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.impl.ThreadsAnalyzer;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ThreadsCommand.
 */
class ThreadsCommandTest {

    @Test
    void testCommandCreation() {
        ThreadsCommand command = new ThreadsCommand();
        assertNotNull(command);
    }

    @Test
    void testGetAnalyzer() {
        ThreadsCommand command = new ThreadsCommand();
        assertTrue(command.getAnalyzer() instanceof ThreadsAnalyzer);
    }

    @Test
    void testCommandLineParsingWithDefaults() {
        ThreadsCommand command = new ThreadsCommand();
        CommandLine cmd = new CommandLine(command);

        // Should show help when no PID provided
        int exitCode = cmd.execute();
        assertEquals(1, exitCode);
    }

    @Test
    void testCommandLineParsingWithOptions() {
        ThreadsCommand command = new ThreadsCommand();
        CommandLine cmd = new CommandLine(command);

        // Test parsing succeeds (execution will fail without valid PID)
        cmd.parseArgs("12345", "--top", "10", "--no-native");

        // Just verify parsing worked
        assertNotNull(command);
    }

    @Test
    void testTopOption() {
        ThreadsCommand command = new ThreadsCommand();
        CommandLine cmd = new CommandLine(command);

        cmd.parseArgs("12345", "--top", "5");

        var options = command.getAdditionalOptions();
        assertEquals(5, options.get("top"));
    }

    @Test
    void testNoNativeOption() {
        ThreadsCommand command = new ThreadsCommand();
        CommandLine cmd = new CommandLine(command);

        cmd.parseArgs("12345", "--no-native");

        var options = command.getAdditionalOptions();
        assertEquals(true, options.get("no-native"));
    }

    @Test
    void testDefaultNoNative() {
        ThreadsCommand command = new ThreadsCommand();
        CommandLine cmd = new CommandLine(command);

        cmd.parseArgs("12345");

        var options = command.getAdditionalOptions();
        assertEquals(false, options.get("no-native"));
    }

    @Test
    void testNoTopOption() {
        ThreadsCommand command = new ThreadsCommand();
        CommandLine cmd = new CommandLine(command);

        cmd.parseArgs("12345");

        var options = command.getAdditionalOptions();
        assertFalse(options.containsKey("top"));
    }

    @Test
    void testInheritedOptions() {
        ThreadsCommand command = new ThreadsCommand();
        CommandLine cmd = new CommandLine(command);

        // Test inherited options from BaseAnalyzerCommand
        cmd.parseArgs("12345", "--dumps", "3", "--interval", "10s", "--keep");

        assertNotNull(command);
    }
}