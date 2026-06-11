// ScheduledExecutorService: fixed-rate task runs much slower than its period.
// Tasks pile up; thread pool saturates; execution keeps falling further behind.
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public class ScheduledTaskPileup {
    public static void main(String[] args) throws Exception {
        var pool = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "sched-worker-" + counter.incrementAndGet());
            t.setDaemon(true); return t;
        });
        // Schedule a task every 200ms that takes 3s — 15x slower than rate
        pool.scheduleAtFixedRate(() -> {
            try { Thread.sleep(3000); } catch (InterruptedException e) {}
        }, 0, 200, TimeUnit.MILLISECONDS);

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
    static AtomicInteger counter = new AtomicInteger();
}
