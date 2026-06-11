// Simulates a service making blocking RPC/HTTP calls with no timeout.
// "rpc-thread-N" threads all sleep in a socket read — classic blocking I/O pattern.
import java.util.concurrent.*;
public class BlockingRpcCall {
    public static void main(String[] args) throws Exception {
        // Simulate 8 threads blocked in a socket read (use ServerSocket that never sends data)
        var latch = new java.util.concurrent.CountDownLatch(1);
        java.net.ServerSocket server = new java.net.ServerSocket(0);
        int port = server.getLocalPort();

        // Accept thread (just accepts, never sends)
        new Thread(() -> {
            while (true) {
                try { server.accept(); } catch (Exception e) { break; }
            }
        }, "rpc-stub-server").start();

        // 8 "RPC callers" all blocked reading from the socket
        for (int i = 0; i < 8; i++) {
            Thread t = new Thread(() -> {
                try {
                    var s = new java.net.Socket("127.0.0.1", port);
                    s.getInputStream().read(); // blocks forever
                } catch (Exception e) {}
            }, "rpc-caller-" + i);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
