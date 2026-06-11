// ReadWriteLock upgrade deadlock: thread holds read lock and tries to
// upgrade to write lock — deadlocks because write lock waits for all readers.
import java.util.concurrent.locks.*;
public class ReadWriteUpgradeDeadlock {
    static final ReentrantReadWriteLock RW = new ReentrantReadWriteLock();

    public static void main(String[] args) throws Exception {
        // Reader that tries to "upgrade" to write while holding read — deadlocks
        Thread upgrader = new Thread(() -> {
            RW.readLock().lock(); // acquires read lock
            try {
                RW.writeLock().lock(); // tries to upgrade — blocks forever (can't while holding read)
            } finally {
                // never reached
            }
        }, "rw-upgrader");
        upgrader.setDaemon(true);
        upgrader.start();
        Thread.sleep(100);

        // More readers — they can still acquire reads, but write is starved
        for (int i = 0; i < 4; i++) {
            Thread r = new Thread(() -> {
                RW.readLock().lock();
                try { try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {} }
                finally { RW.readLock().unlock(); }
            }, "rw-reader-" + i);
            r.setDaemon(true);
            r.start();
        }

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
