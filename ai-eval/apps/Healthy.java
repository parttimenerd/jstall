// Healthy baseline - mostly idle thread pool, no deadlocks, no contention, no hot threads.
import java.util.concurrent.*;

public class Healthy {
    public static void main(String[] args) throws Exception {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        scheduler.scheduleAtFixedRate(() -> {
            // tiny bit of work then idle
            int sum = 0;
            for (int i = 0; i < 1000; i++) sum += i;
        }, 0, 5, TimeUnit.SECONDS);

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
