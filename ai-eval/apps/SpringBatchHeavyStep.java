// Spring Batch-style heavy step: multiple "step-executor" threads each
// doing expensive work. Simulates a Spring Batch parallel step with
// high CPU and a coordinating barrier.
import java.util.concurrent.*;
public class SpringBatchHeavyStep {
    static final CyclicBarrier STEP_BARRIER = new CyclicBarrier(4);

    public static void main(String[] args) throws Exception {
        var pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "step-executor-" + stepCount.incrementAndGet());
            t.setDaemon(true); return t;
        });

        for (int i = 0; i < 4; i++) {
            pool.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    // CPU-heavy chunk processing
                    long sum = 0;
                    for (int j = 0; j < 5_000_000; j++) sum += j;
                    // Sync at end of each "chunk"
                    try { STEP_BARRIER.await(); } catch (Exception e) { break; }
                }
            });
        }

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
    static java.util.concurrent.atomic.AtomicInteger stepCount = new java.util.concurrent.atomic.AtomicInteger();
}
