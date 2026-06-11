// Unbounded thread creation from an executor that re-spawns on failure:
// each task throws, the pool replaces the thread — threads accumulate faster than GC.
import java.util.concurrent.*;
public class ThreadFactoryLeak {
    public static void main(String[] args) throws Exception {
        // Custom executor: every task dies, executor keeps spawning replacement threads
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            0, Integer.MAX_VALUE, 0L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "spawned-thread-" + spawnCount.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        );

        // Submit tasks that block indefinitely — each takes a new thread from unbounded pool
        for (int i = 0; i < 200; i++) {
            pool.submit(() -> {
                try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {}
            });
            Thread.sleep(2);
        }

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
    static java.util.concurrent.atomic.AtomicInteger spawnCount = new java.util.concurrent.atomic.AtomicInteger();
}
