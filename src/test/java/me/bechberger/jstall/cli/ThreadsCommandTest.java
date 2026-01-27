package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.impl.ThreadsAnalyzer;
import org.junit.jupiter.api.Test;

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
        assertInstanceOf(ThreadsAnalyzer.class, command.getAnalyzer());
    }

    @Test
    void testCommandLineParsingWithOptions() {
        ThreadsCommand command = new ThreadsCommand();

        // We don't execute the analyzer (needs a real PID); we just verify parsing doesn't blow up.
        var result = Util.run(command, "12345", "--no-native");

        // It will likely fail later because PID doesn't exist, but parsing must succeed (exit code != 2 for usage error)
        assertNotEquals(2, result.exitCode());
    }

    @Test
    void testNoNativeOption() {
        ThreadsCommand command = new ThreadsCommand();

        Util.run(command, "12345", "--no-native");

        var options = command.getAdditionalOptions();
        assertEquals(true, options.get("no-native"));
    }

    @Test
    void testDefaultNoNative() {
        ThreadsCommand command = new ThreadsCommand();

        Util.run(command, "12345");

        var options = command.getAdditionalOptions();
        assertEquals(false, options.get("no-native"));
    }

    @Test
    void testNoTopOption() {
        ThreadsCommand command = new ThreadsCommand();

        Util.run(command, "12345");

        var options = command.getAdditionalOptions();
        assertFalse(options.containsKey("top"));
    }

    @Test
    void testInheritedOptions() {
        ThreadsCommand command = new ThreadsCommand();

        // Test inherited options from BaseAnalyzerCommand
        var result = Util.run(command, "12345", "--dumps", "3", "--interval", "10s", "--keep");

        assertNotEquals(2, result.exitCode());
    }
}