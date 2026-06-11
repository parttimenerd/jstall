// Periodic tasks take 500ms but scheduled every 50ms -- queue backlog
import java.util.concurrent.*;
public class DeadlineMiss {
    public static void main(String[] args) throws Exception {
        ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);
        for (int i = 0; i < 2; i++) {
            final int n = i;
            sched.scheduleAtFixedRate(() -> {
                Thread.currentThread().setName("dm-worker-" + n);
                try { Thread.sleep(500); } catch (InterruptedException e) {}
            }, 0, 50, TimeUnit.MILLISECONDS);
        }
        Thread monitor = new Thread(() -> {
            try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {}
        }, "dm-monitor");
        monitor.setDaemon(true); monitor.start();
        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
