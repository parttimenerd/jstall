package me.bechberger.jstall.util.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JstallCommandToolTest {

    private ToolCall call(String command, String args) {
        if (args == null) {
            return new ToolCall("id", "run_jstall_command", Map.of("command", command));
        }
        return new ToolCall("id", "run_jstall_command", Map.of("command", command, "args", args));
    }

    @Test
    void blockedCommandAiRejected() {
        JstallCommandTool tool = new JstallCommandTool(false);
        String result = tool.execute(call("ai", null));
        assertTrue(result.startsWith("Error:"), result);
        assertTrue(result.contains("not available"), result);
    }

    @Test
    void blockedInstallMcpRejected() {
        JstallCommandTool tool = new JstallCommandTool(false);
        String result = tool.execute(call("install-claude-mcp", null));
        assertTrue(result.startsWith("Error:"), result);
    }

    @Test
    void blockedInstallSkillRejected() {
        JstallCommandTool tool = new JstallCommandTool(false);
        String result = tool.execute(call("install-claude-skill", null));
        assertTrue(result.startsWith("Error:"), result);
    }

    @Test
    void mutatingCommandWithoutFlagRejected() {
        JstallCommandTool tool = new JstallCommandTool(false);
        String result = tool.execute(call("flame", "12345"));
        assertTrue(result.startsWith("Error:") || result.contains("side effects") || result.contains("--allow-mutations"),
            "Expected rejection: " + result);
    }

    @Test
    void unknownCommandRejected() {
        JstallCommandTool tool = new JstallCommandTool(false);
        String result = tool.execute(call("rm-everything", null));
        assertTrue(result.startsWith("Error:"), result);
    }

    @Test
    void emptyCommandRejected() {
        JstallCommandTool tool = new JstallCommandTool(false);
        String result = tool.execute(call("", null));
        assertTrue(result.startsWith("Error:"), result);
    }

    @Test
    void safeCommandHelp() {
        JstallCommandTool tool = new JstallCommandTool(false);
        // 'help' is safe — it should produce output (not an error prefix)
        String result = tool.execute(call("help", null));
        assertFalse(result.startsWith("Error: command"), result);
    }

    @Test
    void tokenizer() {
        assertArrayEquals(new String[]{"threads", "--top", "5", "12345"},
            JstallCommandTool.tokenize("threads --top 5 12345"));
        assertArrayEquals(new String[]{"record", "create"},
            JstallCommandTool.tokenize("record create"));
        assertArrayEquals(new String[]{"hello world"},
            JstallCommandTool.tokenize("\"hello world\""));
        assertArrayEquals(new String[0], JstallCommandTool.tokenize("  "));
    }

    @Test
    void toolDefinitionContainsSafeList() {
        JstallCommandTool tool = new JstallCommandTool(false);
        String desc = tool.getToolDefinition().description();
        assertTrue(desc.contains("threads"), desc);
        assertTrue(desc.contains("deadlock"), desc);
        assertTrue(desc.contains("gc-heap-info"), desc);
    }
}
