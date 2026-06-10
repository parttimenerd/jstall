// Reader-writer starvation: 1 writer blocked forever by continuous readers.
import java.util.concurrent.locks.*;

public class WriterStarvation {
    public static void main(String[] args) throws Exception {
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock(false); // unfair
        // Many readers
        for (int i = 0; i < 8; i++) {
            final int id = i;
            Thread r = new Thread(() -> {
                while (true) {
                    rw.readLock().lock();
                    try {
                        long s = System.nanoTime();
                        while (System.nanoTime() - s < 5_000_000) {}
                    } finally {
                        rw.readLock().unlock();
                    }
                }
            }, "rw-reader-" + id);
            r.setDaemon(true);
            r.start();
        }
        // 1 writer that gets starved
        Thread w = new Thread(() -> {
            while (true) {
                rw.writeLock().lock();
                try { /* never reached for long */ } finally { rw.writeLock().unlock(); }
            }
        }, "rw-writer");
        w.setDaemon(true);
        w.start();

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
