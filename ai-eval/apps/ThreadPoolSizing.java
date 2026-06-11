// Thread pool with way too many threads (500) all idle — high thread count, no real work.
import java.util.concurrent.*;

public class ThreadPoolSizing {
    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(500, r -> {
            Thread t = new Thread(r, "oversize-worker-" + System.identityHashCode(r) % 10000);
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < 500; i++) {
            pool.submit(() -> {
                try { Thread.sleep(120_000); } catch (InterruptedException e) {}
            });
        }
        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
