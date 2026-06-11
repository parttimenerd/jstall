// Competing timeouts: threads set different lock timeouts and keep retrying,
// creating a thundering-herd pattern on a heavily contended lock.
import java.util.concurrent.locks.*;
import java.util.concurrent.*;
public class LockTimeoutThunder {
    static final ReentrantLock LOCK = new ReentrantLock();

    public static void main(String[] args) throws Exception {
        // Holder: grabs the lock for 3s then releases
        Thread holder = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                LOCK.lock();
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) { break; }
                finally { LOCK.unlock(); }
            }
        }, "timeout-lock-holder");
        holder.setDaemon(true);
        holder.start();
        Thread.sleep(100);

        // 12 "thundering" threads all try with a 5s timeout — all wake at ~same time
        for (int i = 0; i < 12; i++) {
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        if (LOCK.tryLock(5, TimeUnit.SECONDS)) {
                            try { Thread.sleep(10); }
                            finally { LOCK.unlock(); }
                        }
                    } catch (InterruptedException e) { break; }
                }
            }, "timeout-thunder-" + i);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
