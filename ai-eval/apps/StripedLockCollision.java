// 8 stripes, all 20 threads hash to stripe 0
import java.util.concurrent.locks.*;
public class StripedLockCollision {
    static final ReentrantLock[] STRIPES = new ReentrantLock[8];
    static { for (int i = 0; i < 8; i++) STRIPES[i] = new ReentrantLock(); }
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 20; i++) {
            Thread t = new Thread(() -> {
                // All hash to stripe 0 (bug: key % 8 always = 0)
                STRIPES[0].lock();
                try { Thread.sleep(Long.MAX_VALUE); } catch (Exception e) { STRIPES[0].unlock(); }
            }, "slc-worker-" + i);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
