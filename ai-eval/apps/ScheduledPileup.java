// ScheduledExecutor pile-up: schedule short tasks faster than the pool can run them.
// 1 thread handling tasks scheduled every 1ms while each takes 50ms → backlog.
import java.util.concurrent.*;

public class ScheduledPileup {
    public static void main(String[] args) throws Exception {
        ScheduledExecutorService sched = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "sched-worker");
            t.setDaemon(true);
            return t;
        });
        // Schedule fast: 1ms period; each task takes 50ms
        sched.scheduleAtFixedRate(() -> {
            long s = System.nanoTime();
            while (System.nanoTime() - s < 50_000_000) {}
        }, 0, 1, TimeUnit.MILLISECONDS);

        Thread.sleep(1500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
