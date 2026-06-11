// Request-handler threads stuck waiting for slow client data — all in socket read.
// request-handler-N threads blocked reading from a socket that sends data very slowly.
import java.net.*;
import java.io.*;
import java.util.concurrent.*;
public class RequestHandlerTimeout {
    public static void main(String[] args) throws Exception {
        // Server socket that accepts connections; clients connect but don't send data
        ServerSocket server = new ServerSocket(0);
        int port = server.getLocalPort();
        ExecutorService ex = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r); t.setDaemon(true);
            t.setName("request-handler-" + t.getId()); return t;
        });
        // Handler threads waiting for connections and reading
        for (int i = 0; i < 6; i++) {
            ex.submit(() -> {
                try {
                    Socket s = server.accept();
                    s.setSoTimeout(0); // no timeout — will block forever
                    new BufferedReader(new InputStreamReader(s.getInputStream())).readLine();
                } catch (Exception e) {}
            });
        }
        // Client threads that connect but never send data
        for (int i = 0; i < 6; i++) {
            new Thread(() -> {
                try {
                    Socket s = new Socket("localhost", port);
                    Thread.sleep(Long.MAX_VALUE); // connected but silent
                } catch (Exception e) {}
            }).start();
        }
        Thread.sleep(1000);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
