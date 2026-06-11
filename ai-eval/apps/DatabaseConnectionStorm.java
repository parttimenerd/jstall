// Connection storm: many threads try to open new connections simultaneously
// to a slow target (simulated with ServerSocket that delays accept).
// db-connector-N threads all block in socket connect.
public class DatabaseConnectionStorm {
    public static void main(String[] args) throws Exception {
        // Server that accepts slowly
        java.net.ServerSocket server = new java.net.ServerSocket(0);
        int port = server.getLocalPort();

        Thread accepter = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    server.accept();
                    Thread.sleep(5000); // slow accept simulation
                } catch (Exception e) { break; }
            }
        }, "db-accept-slow");
        accepter.setDaemon(true);
        accepter.start();

        // 12 "DB connector" threads all block on connection
        for (int i = 0; i < 12; i++) {
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        new java.net.Socket("127.0.0.1", port);
                        Thread.sleep(2000);
                    } catch (Exception e) { try { Thread.sleep(100); } catch (InterruptedException ie) { break; } }
                }
            }, "db-connector-" + i);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
