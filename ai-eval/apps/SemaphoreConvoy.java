// Semaphore convoy: many threads queue up in strict FIFO order behind a
// slow holder — classic semaphore convoy / lock convoy anti-pattern.
import java.util.concurrent.*;
public class SemaphoreConvoy {
    static final Semaphore GATE = new Semaphore(1, true); // fair=true → convoy

    public static void main(String[] args) throws Exception {
        // Slow holder: holds the permit for 2s then releases
        Thread holder = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    GATE.acquire();
                    Thread.sleep(2000); // slow critical section
                    GATE.release();
                    Thread.sleep(10);
                } catch (InterruptedException e) { break; }
            }
        }, "convoy-holder");
        holder.setDaemon(true);
        holder.start();
        Thread.sleep(100);

        // 10 waiters queued up in FIFO order
        for (int i = 0; i < 10; i++) {
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        GATE.acquire();
                        Thread.sleep(10); // fast critical section
                        GATE.release();
                    } catch (InterruptedException e) { break; }
                }
            }, "convoy-waiter-" + i);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
