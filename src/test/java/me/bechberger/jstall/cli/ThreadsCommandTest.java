package me.bechberger.jstall.cli;

import me.bechberger.jstall.analyzer.impl.ThreadsAnalyzer;
import me.bechberger.minicli.MiniCli;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

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
    void testCommandLineParsingWithOptions() {
        ThreadsCommand command = new ThreadsCommand();

        // We don't execute the analyzer (needs a real PID); we just verify parsing doesn't blow up.
        int exit = MiniCli.run(command, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()),
            new String[]{"12345", "--no-native"});

        // It will likely fail later because PID doesn't exist, but parsing must succeed (exit code != 2 for usage error)
        assertNotEquals(2, exit);
    }

    @Test
    void testNoNativeOption() {
        ThreadsCommand command = new ThreadsCommand();

        MiniCli.run(command, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()),
            new String[]{"12345", "--no-native"});

        var options = command.getAdditionalOptions();
        assertEquals(true, options.get("no-native"));
    }

    @Test
    void testDefaultNoNative() {
        ThreadsCommand command = new ThreadsCommand();

        MiniCli.run(command, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()),
            new String[]{"12345"});

        var options = command.getAdditionalOptions();
        assertEquals(false, options.get("no-native"));
    }

    @Test
    void testNoTopOption() {
        ThreadsCommand command = new ThreadsCommand();

        MiniCli.run(command, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()),
            new String[]{"12345"});

        var options = command.getAdditionalOptions();
        assertFalse(options.containsKey("top"));
    }

    @Test
    void testInheritedOptions() {
        ThreadsCommand command = new ThreadsCommand();

        // Test inherited options from BaseAnalyzerCommand
        int exit = MiniCli.run(command, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()),
            new String[]{"12345", "--dumps", "3", "--interval", "10s", "--keep"});

        assertNotEquals(2, exit);
    }
}