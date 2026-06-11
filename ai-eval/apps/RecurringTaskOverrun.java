// Fixed-rate task taking 3x its period -- queue depth explodes
import java.util.concurrent.*;
public class RecurringTaskOverrun {
    public static void main(String[] args) throws Exception {
        ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);
        for (int i = 0; i < 2; i++) {
            final int n = i;
            sched.scheduleAtFixedRate(() -> {
                Thread.currentThread().setName("rto-task-" + n);
                try { Thread.sleep(600); } catch (InterruptedException e) {} // 3x the 200ms period
            }, 0, 200, TimeUnit.MILLISECONDS);
        }
        Thread.sleep(1000);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
