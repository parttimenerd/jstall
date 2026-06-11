// Threads wait on Condition with 500ms timeout, keep retrying
import java.util.concurrent.locks.*;
import java.util.concurrent.*;
public class ConditionTimeoutLoop {
    static final ReentrantLock LOCK = new ReentrantLock();
    static final Condition COND = LOCK.newCondition();
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 8; i++) {
            Thread t = new Thread(() -> {
                while (true) {
                    LOCK.lock();
                    try { COND.await(500, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException e) { break; }
                    finally { LOCK.unlock(); }
                }
            }, "ctl-waiter-" + i);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
