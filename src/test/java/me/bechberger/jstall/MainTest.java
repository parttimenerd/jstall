package me.bechberger.jstall;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @Test
    void mainDisablesAmbiguousProcessCommands() {
        String propertyName = "jdk.lang.Process.allowAmbiguousCommands";
        String originalValue = System.getProperty(propertyName);
        try {
            System.clearProperty(propertyName);

            assertDoesNotThrow(() -> Main.main(new String[]{"--help"}));

            assertEquals("false", System.getProperty(propertyName));
        } finally {
            if (originalValue == null) {
                System.clearProperty(propertyName);
            } else {
                System.setProperty(propertyName, originalValue);
            }
        }
    }

    @Test
    void fakeSshToolSeesQuotedRemotePayload() throws Exception {
        Path tempDir = Files.createTempDirectory("jstall-fake-ssh-");
        try {
            createFakeTool(tempDir, "ssh");
            createFakeTool(tempDir, "cf");

            String sshOutput = runChildWithFakeTool(
                tempDir,
                Main.class.getName(),
                "--verbose",
                "-s",
                "ssh user@host",
                "list"
            );
            assertTrue(sshOutput.contains("ARGC=2"), sshOutput);
            assertTrue(sshOutput.contains("ARG0=user@host"), sshOutput);
            assertTrue(sshOutput.contains("ARG1=if [ -n \"$JAVA_HOME\""), sshOutput);
            assertTrue(sshOutput.contains("jps"), sshOutput);

            String cfOutput = runChildWithFakeTool(
                tempDir,
                Main.class.getName(),
                "--verbose",
                "--cf",
                "demo-app",
                "list"
            );
            assertTrue(cfOutput.contains("ARGC=4"), cfOutput);
            assertTrue(cfOutput.contains("ARG0=ssh"), cfOutput);
            assertTrue(cfOutput.contains("ARG1=demo-app"), cfOutput);
            assertTrue(cfOutput.contains("ARG2=-c"), cfOutput);
            assertTrue(cfOutput.contains("ARG3=if [ -n \"$JAVA_HOME\""), cfOutput);
            assertTrue(cfOutput.contains("jps"), cfOutput);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static String runChildWithFakeTool(Path fakeToolDir, String mainClass, String... extraArgs) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(me.bechberger.jstall.testframework.TestAppLauncher.getJavaExecutable());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass);
        for (String arg : extraArgs) {
            command.add(arg);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        String originalPath = pb.environment().get("PATH");
        pb.environment().put(
            "PATH",
            fakeToolDir.toAbsolutePath() + java.io.File.pathSeparator + (originalPath != null ? originalPath : "")
        );
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            String originalPathext = pb.environment().get("PATHEXT");
            String preferredPathext = ".CMD;.BAT;.EXE;.COM";
            pb.environment().put(
                "PATHEXT",
                preferredPathext + (originalPathext != null && !originalPathext.isBlank() ? ";" + originalPathext : "")
            );
        }

        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        assertEquals(0, exitCode, output);
        return output;
    }

    private static void createFakeTool(Path directory, String toolName) throws IOException {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path script = directory.resolve(windows ? toolName + ".cmd" : toolName);
        String javaExecutable = me.bechberger.jstall.testframework.TestAppLauncher.getJavaExecutable();
        String classPath = System.getProperty("java.class.path");
        String newline = windows ? "\r\n" : "\n";
        String content;
        if (windows) {
            content = "@echo off" + newline +
                "\"" + javaExecutable + "\" -cp \"" + classPath + "\" me.bechberger.jstall.testframework.FakeToolProbe " + toolName + " %*" + newline +
                "exit /b %ERRORLEVEL%" + newline;
        } else {
            content = "#!/bin/sh" + newline +
                "\"" + javaExecutable + "\" -cp \"" + classPath + "\" me.bechberger.jstall.testframework.FakeToolProbe " + toolName + " \"$@\"" + newline;
        }
        Files.writeString(script, content);
        if (!windows) {
            script.toFile().setExecutable(true);
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }
}