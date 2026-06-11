// HTTP client threads stuck waiting for a response that never comes.
// http-requester-N threads block in socket read with no timeout set.
public class HttpClientTimeout {
    public static void main(String[] args) throws Exception {
        // Minimal HTTP server that accepts but never responds
        java.net.ServerSocket srv = new java.net.ServerSocket(0);
        int port = srv.getLocalPort();

        Thread server = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { srv.accept(); /* Accept but send nothing */ } catch (Exception e) { break; }
            }
        }, "http-black-hole");
        server.setDaemon(true);
        server.start();

        // 8 "HTTP client" threads all blocked reading response
        for (int i = 0; i < 8; i++) {
            Thread t = new Thread(() -> {
                try {
                    var s = new java.net.Socket("127.0.0.1", port);
                    // Send a minimal HTTP request
                    s.getOutputStream().write("GET / HTTP/1.0\r\n\r\n".getBytes());
                    s.getInputStream().read(); // blocks forever — no response
                } catch (Exception e) {}
            }, "http-requester-" + i);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
