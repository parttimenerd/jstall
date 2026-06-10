// Threads waiting on a Condition that is never signalled. They sit in WAITING / parked state with 0 CPU.
import java.util.concurrent.locks.*;

public class ConditionWait {
    public static void main(String[] args) throws Exception {
        ReentrantLock lock = new ReentrantLock();
        Condition cond = lock.newCondition();

        for (int i = 0; i < 4; i++) {
            final int id = i;
            Thread t = new Thread(() -> {
                lock.lock();
                try {
                    cond.awaitUninterruptibly();  // never signalled
                } finally {
                    lock.unlock();
                }
            }, "cond-waiter-" + id);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
