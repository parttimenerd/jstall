// Netty-style event loop: single I/O thread blocks on a synchronous call,
// starving all I/O event processing (no real network, simulated with sleep).
import java.util.concurrent.*;
public class EventLoopBlocked {
    public static void main(String[] args) throws Exception {
        // Single-threaded event loop (simulates Netty boss thread)
        ExecutorService loop = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "io-event-loop-1");
            t.setDaemon(true);
            return t;
        });

        // Submit a blocking task that ties up the loop
        loop.submit(() -> {
            try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {}
        });

        // Queue 8 pending I/O tasks that can never run
        for (int i = 0; i < 8; i++) {
            loop.submit(() -> System.out.println("event handled"));
        }

        Thread.sleep(400);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
