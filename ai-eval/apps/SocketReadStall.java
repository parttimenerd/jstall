// Threads blocked on socket.read() with no data
import java.net.*;
public class SocketReadStall {
    public static void main(String[] args) throws Exception {
        ServerSocket server = new ServerSocket(0);
        int port = server.getLocalPort();
        for (int i = 0; i < 8; i++) {
            final int n = i;
            Thread t = new Thread(() -> {
                try {
                    Socket s = new Socket("127.0.0.1", port);
                    s.setSoTimeout(0);
                    s.getInputStream().read(); // blocks forever
                } catch (Exception e) {}
            }, "srs-reader-" + n);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
        server.close();
    }
}
