// executor.awaitTermination() hangs because submitted tasks never finish.
// executor-shutdown-waiter is stuck in awaitTermination(); exec-task-N threads run forever.
import java.util.concurrent.*;
public class ExecutorAwaitStall {
    public static void main(String[] args) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r); t.setDaemon(true);
            t.setName("exec-task-" + t.getId()); return t;
        });
        // Submit tasks that never finish
        for (int i = 0; i < 4; i++) {
            ex.submit(() -> { try { Thread.sleep(Long.MAX_VALUE); } catch (Exception e) {} });
        }
        ex.shutdown();
        Thread waiter = new Thread(() -> {
            try { ex.awaitTermination(2, TimeUnit.MINUTES); } catch (Exception e) {}
        }, "executor-shutdown-waiter");
        waiter.setDaemon(true);
        waiter.start();
        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
