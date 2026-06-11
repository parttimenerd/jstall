// Two threads in a tight tryLock loop — each sees the other holding the lock
// and spins retrying. Wastes CPU with no progress (livelock variant).
import java.util.concurrent.locks.*;
public class TryLockLivelock {
    static final ReentrantLock LOCK_A = new ReentrantLock();
    static final ReentrantLock LOCK_B = new ReentrantLock();

    public static void main(String[] args) throws Exception {
        // Thread 1: tries A then B
        Thread t1 = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                if (LOCK_A.tryLock()) {
                    try {
                        Thread.yield();
                        if (LOCK_B.tryLock()) {
                            try { Thread.sleep(1); } catch (InterruptedException e) { break; }
                            finally { LOCK_B.unlock(); }
                        }
                    } finally { LOCK_A.unlock(); }
                }
            }
        }, "trylock-thread-1");

        // Thread 2: tries B then A (reverse order — livelock)
        Thread t2 = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                if (LOCK_B.tryLock()) {
                    try {
                        Thread.yield();
                        if (LOCK_A.tryLock()) {
                            try { Thread.sleep(1); } catch (InterruptedException e) { break; }
                            finally { LOCK_A.unlock(); }
                        }
                    } finally { LOCK_B.unlock(); }
                }
            }
        }, "trylock-thread-2");

        t1.setDaemon(true); t2.setDaemon(true);
        t1.start(); t2.start();

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
