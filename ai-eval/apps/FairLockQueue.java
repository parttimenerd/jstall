// ReentrantLock(fair=true) creates FIFO queue of 20 waiters
import java.util.concurrent.locks.*;
public class FairLockQueue {
    static final ReentrantLock LOCK = new ReentrantLock(true);
    public static void main(String[] args) throws Exception {
        LOCK.lock(); // main holds it
        for (int i = 0; i < 20; i++) {
            Thread t = new Thread(() -> {
                LOCK.lock();
                try { Thread.sleep(100); } catch (Exception e) {} finally { LOCK.unlock(); }
            }, "flq-waiter-" + i);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
