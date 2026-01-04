package me.bechberger.jstall.testframework;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Framework for launching test Java applications and capturing thread dumps.
 */
public class TestAppLauncher {

    private Process process;
    private long pid;
    private final List<String> output = new ArrayList<>();

    /**
     * Launches a test application.
     *
     * @param mainClass The main class to run (e.g., "me.bechberger.jstall.testapp.DeadlockTestApp")
     * @param args Arguments to pass to the application
     * @return The launcher instance
     */
    public TestAppLauncher launch(String mainClass, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(getJavaExecutable());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass);
        command.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        process = pb.start();
        pid = process.pid();

        // Start reading output in background
        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        output.add(line);
                    }
                    System.out.println("[TestApp " + pid + "] " + line);
                }
            } catch (IOException e) {
                // Process terminated
            }
        });
        outputReader.setDaemon(true);
        outputReader.start();

        return this;
    }

    /**
     * Waits for the application to be ready (prints "started" message).
     */
    public TestAppLauncher waitUntilReady(long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            synchronized (output) {
                if (output.stream().anyMatch(line -> line.contains("started"))) {
                    return this;
                }
            }
            Thread.sleep(100);
        }
        throw new RuntimeException("Application did not start within " + timeoutMs + "ms");
    }

    /**
     * Captures a thread dump using jstack.
     */
    public String captureThreadDump() throws IOException, InterruptedException {
        return captureThreadDumpWithJcmd();
    }

    /**
     * Captures a thread dump using jcmd (preferred method).
     */
    private String captureThreadDumpWithJcmd() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("jcmd", String.valueOf(pid), "Thread.print");
        Process jcmd = pb.start();

        String output = new String(jcmd.getInputStream().readAllBytes());
        int exitCode = jcmd.waitFor();

        if (exitCode != 0) {
            throw new IOException("jcmd failed with exit code " + exitCode);
        }

        return output;
    }

    /**
     * Captures multiple thread dumps with a delay between them.
     */
    public List<String> captureMultipleThreadDumps(int count, long intervalMs) throws IOException, InterruptedException {
        List<String> dumps = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                Thread.sleep(intervalMs);
            }
            dumps.add(captureThreadDump());
        }
        return dumps;
    }

    /**
     * Saves a thread dump to a file.
     */
    public void saveThreadDump(String dump, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, dump);
        System.out.println("Saved thread dump to: " + outputPath);
    }

    /**
     * Saves multiple thread dumps to files.
     */
    public void saveThreadDumps(List<String> dumps, Path baseDir, String prefix) throws IOException {
        Files.createDirectories(baseDir);
        for (int i = 0; i < dumps.size(); i++) {
            Path outputPath = baseDir.resolve(String.format("%s-%03d.txt", prefix, i));
            Files.writeString(outputPath, dumps.get(i));
            System.out.println("Saved thread dump to: " + outputPath);
        }
    }

    /**
     * Gets the PID of the launched process.
     */
    public long getPid() {
        return pid;
    }

    /**
     * Stops the test application.
     */
    public void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Gets the Java executable path.
     */
    private String getJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        return javaHome + "/bin/java";
    }

    /**
     * AutoCloseable support.
     */
    public void close() {
        stop();
    }
}