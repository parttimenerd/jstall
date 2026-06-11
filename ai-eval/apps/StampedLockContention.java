// StampedLock with a long-held write lock blocking multiple optimistic readers.
import java.util.concurrent.locks.StampedLock;

public class StampedLockContention {
    static final StampedLock lock = new StampedLock();

    public static void main(String[] args) throws Exception {
        // Writer holds the write lock for a long time
        Thread writer = new Thread(() -> {
            long stamp = lock.writeLock();
            try { Thread.sleep(120_000); } catch (InterruptedException e) {}
            finally { lock.unlockWrite(stamp); }
        }, "sl-writer");
        writer.setDaemon(true);
        writer.start();

        Thread.sleep(200);

        // Readers block waiting for write lock to release
        for (int i = 0; i < 6; i++) {
            final int id = i;
            Thread r = new Thread(() -> {
                long stamp = lock.readLock();
                try { Thread.sleep(120_000); } catch (InterruptedException e) {}
                finally { lock.unlockRead(stamp); }
            }, "sl-reader-" + id);
            r.setDaemon(true);
            r.start();
        }

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
