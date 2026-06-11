// Thread waits on Object.wait() in a loop but the condition is never set true —
// periodic spurious wakeups happen but the real signal never arrives.
import java.util.*;
public class MonitorSpuriousWakeup {
    static final Object LOCK = new Object();
    static boolean ready = false;

    public static void main(String[] args) throws Exception {
        // 5 waiters: check condition, always false, re-wait
        for (int i = 0; i < 5; i++) {
            Thread t = new Thread(() -> {
                synchronized (LOCK) {
                    while (!ready) {
                        try { LOCK.wait(30_000); } // 30s timeout — wakes up but condition still false
                        catch (InterruptedException e) { break; }
                    }
                }
            }, "spurious-waiter-" + i);
            t.setDaemon(true);
            t.start();
        }

        // Notifier that sets condition but NOT ready — spurious wakeup simulation
        Thread notifier = new Thread(() -> {
            while (true) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
                synchronized (LOCK) { LOCK.notifyAll(); } // wakes all but ready stays false
            }
        }, "spurious-notifier");
        notifier.setDaemon(true);
        notifier.start();

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
