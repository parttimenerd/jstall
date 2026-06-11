// WebSocket-style connection leak: 50 ws-client-N threads hold open socket
// connections that are never closed — file descriptor accumulation.
public class WebSocketConnectionLeak {
    static final java.util.List<java.net.Socket> CONNECTIONS = new java.util.ArrayList<>();

    public static void main(String[] args) throws Exception {
        java.net.ServerSocket server = new java.net.ServerSocket(0);
        int port = server.getLocalPort();

        Thread accepter = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { server.accept(); } catch (Exception e) { break; }
            }
        }, "ws-server");
        accepter.setDaemon(true);
        accepter.start();

        for (int i = 0; i < 50; i++) {
            Thread t = new Thread(() -> {
                try {
                    var s = new java.net.Socket("127.0.0.1", port);
                    synchronized (CONNECTIONS) { CONNECTIONS.add(s); }
                    Thread.sleep(Long.MAX_VALUE);
                } catch (Exception e) {}
            }, "ws-client-" + i);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
