// Metrics contention: many threads incrementing a shared counter under lock.
// Simulates Micrometer/Prometheus counter write contention pattern.
import java.util.concurrent.atomic.*;
public class MicrometerMetricContention {
    // Simulated synchronized counter (like old metrics implementations)
    static long hits = 0;
    static final Object METRIC_LOCK = new Object();

    public static void main(String[] args) throws Exception {
        // 30 threads all banging the same synchronized counter
        for (int i = 0; i < 30; i++) {
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    synchronized (METRIC_LOCK) { hits++; }
                }
            }, "metric-recorder-" + i);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(400);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
