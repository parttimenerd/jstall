// ExecutorService shut down but awaitTermination hangs because workers never finish.
import java.util.concurrent.*;

public class ExecutorShutdownStall {
    public static void main(String[] args) throws Exception {
        ExecutorService exec = Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "shutdown-worker-" + System.identityHashCode(r) % 1000);
            t.setDaemon(true);
            return t;
        });

        // Submit tasks that never finish
        for (int i = 0; i < 3; i++) {
            exec.submit(() -> {
                try { Thread.sleep(120_000); } catch (InterruptedException e) {}
            });
        }

        Thread.sleep(300);
        exec.shutdown();

        // Main thread calls awaitTermination — blocks because workers are stuck
        Thread waiter = new Thread(() -> {
            try {
                exec.awaitTermination(120, TimeUnit.SECONDS);
            } catch (InterruptedException e) {}
        }, "shutdown-awaiter");
        waiter.setDaemon(true);
        waiter.start();

        Thread.sleep(300);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
