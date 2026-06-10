// Slow I/O: many threads blocked in socket read on a server that never responds.
// They sit in RUNNABLE state in native socketRead0 — classic "RUNNABLE but actually blocked on I/O" trap.
import java.net.*;
import java.io.*;

public class SlowIO {
    public static void main(String[] args) throws Exception {
        // Start a server that accepts but never writes
        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();
        Thread acceptor = new Thread(() -> {
            while (true) {
                try { ss.accept(); /* don't close, hold the socket */ }
                catch (IOException e) { return; }
            }
        }, "silent-server-accept");
        acceptor.setDaemon(true);
        acceptor.start();

        for (int i = 0; i < 8; i++) {
            final int id = i;
            Thread client = new Thread(() -> {
                try {
                    Socket s = new Socket("127.0.0.1", port);
                    InputStream in = s.getInputStream();
                    byte[] buf = new byte[1024];
                    int n = in.read(buf);  // never returns
                    if (n > 0) System.out.println(n);
                } catch (IOException e) { }
            }, "io-reader-" + id);
            client.setDaemon(true);
            client.start();
        }

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
