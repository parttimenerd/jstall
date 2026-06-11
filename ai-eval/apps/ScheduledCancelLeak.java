// Many ScheduledFutures cancelled but kept in queue -- heap grows
import java.util.concurrent.*;
import java.util.*;
public class ScheduledCancelLeak {
    public static void main(String[] args) throws Exception {
        ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);
        List<ScheduledFuture<?>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int n = i;
            ScheduledFuture<?> f = sched.scheduleAtFixedRate(() -> {
                Thread.currentThread().setName("scl-worker-" + n);
                try { Thread.sleep(100); } catch (InterruptedException e) {}
            }, 0, 500, TimeUnit.MILLISECONDS);
            futures.add(f);
        }
        Thread.sleep(500);
        // Cancel all -- but ScheduledExecutor keeps cancelled tasks in queue
        futures.forEach(f -> f.cancel(false));
        Thread.sleep(300);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
