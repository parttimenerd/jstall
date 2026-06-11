// Semaphore-based bulkhead at capacity; request threads waiting for entry permit.
// bulkhead-request-N threads WAITING on Semaphore.acquire() — bulkhead-worker-N hold all permits.
import java.util.concurrent.*;
public class BulkheadStall {
    public static void main(String[] args) throws Exception {
        Semaphore bulkhead = new Semaphore(4); // max 4 concurrent
        // Acquire all permits and hold them (simulating busy workers)
        for (int i = 0; i < 4; i++) {
            final int n = i;
            Thread worker = new Thread(() -> {
                try {
                    bulkhead.acquire();
                    Thread.sleep(Long.MAX_VALUE); // holds permit forever
                } catch (Exception e) {}
            }, "bulkhead-worker-" + n);
            worker.setDaemon(true);
            worker.start();
        }
        Thread.sleep(100);
        // Request threads that can't get a permit
        for (int i = 0; i < 8; i++) {
            final int n = i;
            Thread req = new Thread(() -> {
                try {
                    bulkhead.acquire(); // blocked — bulkhead full
                } catch (Exception e) {}
            }, "bulkhead-request-" + n);
            req.setDaemon(true);
            req.start();
        }
        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
