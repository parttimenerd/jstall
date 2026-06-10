package me.bechberger.jstall.util.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SourceToolsTest {

    @TempDir
    Path tmp;

    private SourceTools tools() {
        return new SourceTools(tmp);
    }

    private ToolCall call(String name, String... kvPairs) {
        var args = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < kvPairs.length - 1; i += 2) {
            args.put(kvPairs[i], kvPairs[i + 1]);
        }
        return new ToolCall("id", name, args);
    }

    @Test
    void listJavaFiles() throws Exception {
        Files.writeString(tmp.resolve("Foo.java"), "class Foo {}");
        Files.writeString(tmp.resolve("Bar.java"), "class Bar {}");
        Files.writeString(tmp.resolve("notes.txt"), "text");

        ToolExecutor exec = tools().createExecutor();
        String result = exec.execute(call("list_source_files", "pattern", "**/*.java"));
        assertTrue(result.contains("Foo.java"), result);
        assertTrue(result.contains("Bar.java"), result);
        assertFalse(result.contains("notes.txt"), result);
    }

    @Test
    void listDefaultPatternSkipsNonSource() throws Exception {
        Files.writeString(tmp.resolve("Main.java"), "class Main {}");
        Files.writeString(tmp.resolve("config.yml"), "key: val");

        ToolExecutor exec = tools().createExecutor();
        String result = exec.execute(call("list_source_files"));
        assertTrue(result.contains("Main.java"), result);
        assertFalse(result.contains("config.yml"), result);
    }

    @Test
    void listSkipsTargetDir() throws Exception {
        Path targetDir = Files.createDirectory(tmp.resolve("target"));
        Files.writeString(targetDir.resolve("Generated.java"), "class Generated {}");
        Files.writeString(tmp.resolve("Real.java"), "class Real {}");

        ToolExecutor exec = tools().createExecutor();
        String result = exec.execute(call("list_source_files", "pattern", "**/*.java"));
        assertTrue(result.contains("Real.java"), result);
        assertFalse(result.contains("Generated.java"), result);
    }

    @Test
    void readFile() throws Exception {
        Files.writeString(tmp.resolve("Hello.java"), "line1\nline2\nline3\n");

        ToolExecutor exec = tools().createExecutor();
        String result = exec.execute(call("read_source_file", "path", "Hello.java"));
        assertTrue(result.contains("line1"), result);
        assertTrue(result.contains("line2"), result);
        assertTrue(result.contains("line3"), result);
    }

    @Test
    void readFileWithLineRange() throws Exception {
        String content = "a\nb\nc\nd\ne\n";
        Files.writeString(tmp.resolve("Lines.java"), content);

        ToolExecutor exec = tools().createExecutor();
        // ToolCall with integer args
        var args = new java.util.LinkedHashMap<String, Object>();
        args.put("path", "Lines.java");
        args.put("start_line", 2);
        args.put("end_line", 3);
        String result = exec.execute(new ToolCall("id", "read_source_file", args));
        assertTrue(result.contains("b"), result);
        assertTrue(result.contains("c"), result);
        assertFalse(result.contains("a\n"), result); // line 1 should not appear before "b"
    }

    @Test
    void pathTraversalRejected() throws Exception {
        ToolExecutor exec = tools().createExecutor();
        String result = exec.execute(call("read_source_file", "path", "../etc/passwd"));
        assertTrue(result.startsWith("Error:") && result.contains("traversal"),
            "Expected traversal error but got: " + result);
    }

    @Test
    void absolutePathAllowedForSourceFiles() throws Exception {
        // Write a real .java file at an absolute path and read it
        Path tmpFile = Files.createTempFile("TestClass", ".java");
        Files.writeString(tmpFile, "class TestClass { void foo() {} }");
        try {
            ToolExecutor exec = tools().createExecutor();
            String result = exec.execute(call("read_source_file", "path", tmpFile.toString()));
            assertTrue(result.contains("TestClass"), "Expected file content but got: " + result);
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    @Test
    void grepFindsMatches() throws Exception {
        Files.writeString(tmp.resolve("A.java"), "synchronized void foo() {}");
        Files.writeString(tmp.resolve("B.java"), "void bar() {}");

        ToolExecutor exec = tools().createExecutor();
        String result = exec.execute(call("grep_source", "pattern", "synchronized"));
        assertTrue(result.contains("A.java"), result);
        assertFalse(result.contains("B.java"), result);
    }

    @Test
    void grepNoMatches() throws Exception {
        Files.writeString(tmp.resolve("A.java"), "void foo() {}");

        ToolExecutor exec = tools().createExecutor();
        // Use a pattern that won't exist in any real .java file in /tmp either
        String result = exec.execute(call("grep_source", "pattern",
            "XYZZY_IMPOSSIBLE_PATTERN_99999_JSTALL_TEST"));
        assertTrue(result.toLowerCase().contains("no match"), result);
    }

    @Test
    void grepInvalidRegex() throws Exception {
        ToolExecutor exec = tools().createExecutor();
        String result = exec.execute(call("grep_source", "pattern", "[invalid(regex"));
        assertTrue(result.startsWith("Error:"), result);
    }

    @Test
    void listCapAt200() throws Exception {
        for (int i = 0; i < 250; i++) {
            Files.writeString(tmp.resolve("File" + i + ".java"), "class F" + i + " {}");
        }
        ToolExecutor exec = tools().createExecutor();
        String result = exec.execute(call("list_source_files", "pattern", "**/*.java"));
        // Count occurrences of ".java"
        int count = result.split("\\.java").length - 1;
        assertTrue(count <= 200, "Should cap at 200 but got: " + count);
    }
}
