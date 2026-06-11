// Deadlock between synchronized monitor and ReentrantLock
import java.util.concurrent.locks.*;
public class MixedLockDeadlock {
    static final Object MONITOR = new Object();
    static final ReentrantLock RLOCK = new ReentrantLock();
    public static void main(String[] args) throws Exception {
        Thread a = new Thread(() -> {
            synchronized(MONITOR) {
                try { Thread.sleep(100); } catch (Exception e) {}
                RLOCK.lock(); // will block
            }
        }, "mld-sync-holder");
        Thread b = new Thread(() -> {
            RLOCK.lock();
            try { Thread.sleep(100); } catch (Exception e) {}
            synchronized(MONITOR) {} // will block
        }, "mld-reentrant-holder");
        a.setDaemon(true); b.setDaemon(true);
        a.start(); b.start();
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
