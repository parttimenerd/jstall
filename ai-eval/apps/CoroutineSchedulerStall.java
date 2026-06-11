// Fiber / coroutine-style cooperative scheduler stall: all "fibers" are parked
// waiting for a scheduler thread that is itself blocked.
import java.util.concurrent.*;
public class CoroutineSchedulerStall {
    static final SynchronousQueue<Runnable> SCHEDULER_QUEUE = new SynchronousQueue<>();

    public static void main(String[] args) throws Exception {
        // Scheduler thread: blocked trying to take from empty queue (the scheduler is stuck)
        Thread scheduler = new Thread(() -> {
            try {
                // Scheduler blocks waiting for its own "wake-up" signal that never comes
                Object signal = new Object();
                synchronized (signal) { signal.wait(); }
            } catch (InterruptedException e) {}
        }, "coroutine-scheduler");
        scheduler.setDaemon(true);
        scheduler.start();
        Thread.sleep(100);

        // 8 "fibers" parked waiting for the scheduler to resume them
        for (int i = 0; i < 8; i++) {
            Thread fiber = new Thread(() -> {
                try {
                    SCHEDULER_QUEUE.put(() -> {}); // blocks — scheduler never takes
                } catch (InterruptedException e) {}
            }, "fiber-" + i);
            fiber.setDaemon(true);
            fiber.start();
        }

        Thread.sleep(400);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
