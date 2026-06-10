// ReentrantLock contention (different from synchronized). Many threads tryLock/lock the same ReentrantLock.
import java.util.concurrent.locks.*;

public class ReentrantContention {
    static final ReentrantLock LOCK = new ReentrantLock();

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 6; i++) {
            final int id = i;
            Thread t = new Thread(() -> {
                while (true) {
                    LOCK.lock();
                    try {
                        // hold for ~5ms
                        long start = System.nanoTime();
                        while (System.nanoTime() - start < 5_000_000) {}
                    } finally {
                        LOCK.unlock();
                    }
                }
            }, "rl-contender-" + id);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
