// Batch job stall: coordinator thread waits for worker results that never come
// because workers are stuck on I/O. Simulates a Spring Batch step hanging.
import java.util.concurrent.*;
public class BatchJobStall {
    public static void main(String[] args) throws Exception {
        var futures = new java.util.ArrayList<Future<String>>();
        var pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "batch-worker-" + workerCount.incrementAndGet());
            t.setDaemon(true);
            return t;
        });

        // Submit 4 worker tasks that all block
        for (int i = 0; i < 4; i++) {
            futures.add(pool.submit(() -> {
                Thread.sleep(Long.MAX_VALUE); // simulates stuck I/O
                return "done";
            }));
        }

        // Coordinator blocks waiting for all results
        Thread coord = new Thread(() -> {
            try {
                for (var f : futures) f.get(); // blocks forever
            } catch (Exception e) {}
        }, "batch-coordinator");
        coord.setDaemon(true);
        coord.start();

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
    static java.util.concurrent.atomic.AtomicInteger workerCount = new java.util.concurrent.atomic.AtomicInteger();
}
