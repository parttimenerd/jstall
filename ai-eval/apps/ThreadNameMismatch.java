// Thread named "idle-worker" but actually BLOCKED on a lock — misleading name.
// idle-worker-N threads are BLOCKED on a mutex despite their "idle" name.
import java.util.concurrent.locks.ReentrantLock;
public class ThreadNameMismatch {
    static final ReentrantLock lock = new ReentrantLock();
    public static void main(String[] args) throws Exception {
        // Holder: acquires lock and sleeps
        Thread holder = new Thread(() -> {
            lock.lock();
            try { Thread.sleep(Long.MAX_VALUE); } catch (Exception e) {} finally { lock.unlock(); }
        }, "lock-holder-background");
        holder.setDaemon(true);
        holder.start();
        Thread.sleep(100);
        // "idle" workers that are actually contending
        for (int i = 0; i < 6; i++) {
            final int n = i;
            Thread t = new Thread(() -> {
                lock.lock(); // BLOCKED on a lock, despite "idle" name
                try { Thread.sleep(1); } catch (Exception e) {} finally { lock.unlock(); }
            }, "idle-worker-" + n);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
