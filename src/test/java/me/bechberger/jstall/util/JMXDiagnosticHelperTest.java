package me.bechberger.jstall.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JMXDiagnosticHelperTest {

    @Test
    void remoteExecutorFallsBackToJcmd() throws Exception {
        RecordingRemoteExecutor executor = new RecordingRemoteExecutor();

        JMXDiagnosticHelper helper = new JMXDiagnosticHelper(executor, 12345L);
        String output = helper.executeCommand("VM.uptime");

        assertEquals("vm-uptime ok", output);
        assertEquals("jcmd", executor.command);
        assertEquals(2, executor.args.length);
        assertEquals("12345", executor.args[0]);
        assertEquals("VM.uptime", executor.args[1]);
        assertTrue(executor.describeCommand("jcmd", "12345", "VM.uptime").contains("jcmd"));
    }

    private static final class RecordingRemoteExecutor extends CommandExecutor {
        private String command;
        private String[] args;

        private RecordingRemoteExecutor() {
            super(true);
        }

        @Override
        public CommandResult executeCommand(String command, String... args) {
            this.command = command;
            this.args = args != null ? args.clone() : new String[0];
            return new CommandResult("vm-uptime ok", "", 0, 0);
        }

        @Override
        public String describeCommand(String command, String... args) {
            if (args == null || args.length == 0) {
                return command;
            }
            return command + " " + String.join(" ", args);
        }

        @Override
        public TemporaryFile createTemporaryFile(String prefix, String suffix) throws IOException {
            throw new UnsupportedOperationException("not needed in this test");
        }
    }
}