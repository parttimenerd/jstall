package me.bechberger.jstall.util.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Launches a llama-server (llama.cpp) process in the background if one is not already running.
 *
 * <p>Checks if llama-server is available on PATH, optionally starts it with a HuggingFace model
 * spec ({@code -hf repo:quant}), and waits for the health endpoint to become ready.
 */
public final class LlamaServerLauncher {

    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration STARTUP_POLL = Duration.ofSeconds(2);
    private static final int MAX_STARTUP_WAIT_SECONDS = 300; // 5 minutes for large models

    private LlamaServerLauncher() {
    }

    /**
     * Returns true if {@code llama-server} is found on the system PATH.
     */
    public static boolean isInstalled() {
        try {
            Process p = new ProcessBuilder("which", "llama-server")
                .redirectErrorStream(true)
                .start();
            int exit = p.waitFor();
            return exit == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /**
     * Returns true if llama-server at the given host is responding to health checks.
     */
    public static boolean isRunning(String host) {
        try {
            URI uri = URI.create(host.endsWith("/") ? host.substring(0, host.length() - 1) : host);
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(HEALTH_TIMEOUT)
                .build();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(uri.resolve("/health"))
                .timeout(HEALTH_TIMEOUT)
                .GET()
                .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Ensures a llama-server is running. If one is already responding at the given host,
     * returns immediately. Otherwise, launches one in the background.
     *
     * @param host       the host:port to use (e.g. "http://127.0.0.1:8080")
     * @param hfModel    HuggingFace model spec for {@code -hf}, e.g. "AaryanK/Qwen3.5-9B-GGUF:Q8_0"
     *                   or "AaryanK/Qwen3.5-4B-GGUF:Q8_0" for a smaller/faster alternative.
     *                   If null, llama-server is started without {@code -hf} (server must have a model configured).
     * @return the Process handle if a new server was launched, or null if one was already running
     * @throws IOException if llama-server is not installed or fails to start
     */
    public static Process ensureRunning(String host, String hfModel) throws IOException {
        if (isRunning(host)) {
            return null; // already running
        }

        if (!isInstalled()) {
            throw new IOException(
                "llama-server is not installed or not on PATH. "
                + "Install it from https://github.com/ggerganov/llama.cpp");
        }

        // Parse port from host URL
        URI uri = URI.create(host.endsWith("/") ? host.substring(0, host.length() - 1) : host);
        int port = uri.getPort();
        if (port == -1) {
            port = 8080;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("llama-server");
        cmd.add("--port");
        cmd.add(String.valueOf(port));
        if (hfModel != null && !hfModel.isBlank()) {
            cmd.add("-hf");
            cmd.add(hfModel);
        }

        // Inherit HF_HOME / HF_HUB_CACHE so model downloads are cached
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO(); // let server logs go to stderr/stdout of the parent
        // Redirect to /dev/null if we want quiet operation:
        pb.redirectOutput(ProcessBuilder.Redirect.to(Path.of("/dev/null").toFile()));
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        // Forward cache env vars
        String hfHome = System.getenv("HF_HOME");
        if (hfHome != null) {
            pb.environment().put("HF_HOME", hfHome);
        }
        String hfHubCache = System.getenv("HF_HUB_CACHE");
        if (hfHubCache != null) {
            pb.environment().put("HF_HUB_CACHE", hfHubCache);
        }

        System.err.println("Starting llama-server" +
            (hfModel != null ? " with model " + hfModel : "") +
            " on port " + port + "...");

        Process process = pb.start();

        // Register shutdown hook to kill the server when the JVM exits
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (process.isAlive()) {
                process.destroy();
            }
        }));

        // Wait for health endpoint
        long deadline = System.currentTimeMillis() + MAX_STARTUP_WAIT_SECONDS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("llama-server exited prematurely with code " + process.exitValue());
            }
            if (isRunning(host)) {
                System.err.println("llama-server is ready.");
                return process;
            }
            try {
                Thread.sleep(STARTUP_POLL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroy();
                throw new IOException("Interrupted while waiting for llama-server to start", e);
            }
        }

        process.destroy();
        throw new IOException("llama-server failed to become ready within " + MAX_STARTUP_WAIT_SECONDS + " seconds");
    }
}
