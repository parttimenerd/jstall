// Fixed pool of 2 threads, all stuck doing slow work. Tasks pile up in the queue.
import java.util.concurrent.*;

public class PoolStarvation {
    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "starved-pool-" + System.identityHashCode(r) % 1000);
            t.setDaemon(true);
            return t;
        });
        // Submit way more tasks than the pool can handle - each task sleeps for 30s
        for (int i = 0; i < 100; i++) {
            pool.submit(() -> {
                try { Thread.sleep(30_000); } catch (InterruptedException e) {}
            });
        }
        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
