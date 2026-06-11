// ForkJoinPool.commonPool() with many tasks doing synchronized I/O — all carriers
// block on the same monitor causing severe contention in work-stealing.
import java.util.concurrent.*;
public class WorkStealingContention {
    static final Object SHARED_LOCK = new Object();

    public static void main(String[] args) throws Exception {
        var pool = ForkJoinPool.commonPool();
        // Submit 50 tasks that each try to enter the same synchronized block
        for (int i = 0; i < 50; i++) {
            pool.submit(() -> {
                synchronized (SHARED_LOCK) {
                    try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {}
                }
            });
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
