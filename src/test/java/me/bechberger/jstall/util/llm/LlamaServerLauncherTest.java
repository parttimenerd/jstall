package me.bechberger.jstall.util.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LlamaServerLauncherTest {

    @Test
    void testIsRunningReturnsTrueWhenHealthy() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        try {
            assertThat(LlamaServerLauncher.isRunning("http://127.0.0.1:" + port)).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testIsRunningReturnsFalseWhenNoServer() {
        // Use a port that's very unlikely to be in use
        assertThat(LlamaServerLauncher.isRunning("http://127.0.0.1:19999")).isFalse();
    }

    @Test
    void testEnsureRunningSkipsIfAlreadyRunning() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();

        try {
            // Should return null (no process launched) when server is already running
            Process p = LlamaServerLauncher.ensureRunning("http://127.0.0.1:" + port, null);
            assertThat(p).isNull();
        } finally {
            server.stop(0);
        }
    }
}
