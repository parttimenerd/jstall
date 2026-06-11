// Single-threaded executor (event loop) whose thread is blocked on a slow task — all queued tasks starve.
import java.util.concurrent.*;

public class SingleThreadedStall {
    public static void main(String[] args) throws Exception {
        // Single-threaded pool — only one worker named "event-loop-1"
        ExecutorService loop = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "event-loop-1");
            t.setDaemon(true);
            return t;
        });

        // First task: slow blocking call — monopolizes the single thread
        loop.submit(() -> {
            try { Thread.sleep(120_000); } catch (InterruptedException e) {}
        });

        // 8 follow-on tasks queue up but never execute
        for (int i = 0; i < 8; i++) {
            loop.submit(() -> System.out.println("task"));
        }

        // A separate "main-waiter" thread calls awaitTermination — also blocks
        Thread awaiter = new Thread(() -> {
            loop.shutdown();
            try { loop.awaitTermination(120, TimeUnit.SECONDS); } catch (InterruptedException e) {}
        }, "main-awaiter");
        awaiter.setDaemon(true);
        awaiter.start();

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
