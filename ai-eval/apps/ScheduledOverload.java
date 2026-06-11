// ScheduledThreadPool 2 threads, many fixedRate tasks each taking 3s (period=100ms)
import java.util.concurrent.*;
public class ScheduledOverload {
    public static void main(String[] args) throws Exception {
        ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);
        for (int i = 0; i < 2; i++) {
            final int n = i;
            sched.scheduleAtFixedRate(() -> {
                Thread.currentThread().setName("so-worker-" + n);
                try { Thread.sleep(3000); } catch (InterruptedException e) {}
            }, 0, 100, TimeUnit.MILLISECONDS);
        }
        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
