// Livelock: two threads keep yielding to each other. Each tries to acquire its
// "outer" lock, and if it does, will release it if the other thread is waiting.
// They never make progress. Each thread burns CPU.
import java.util.concurrent.atomic.AtomicBoolean;

public class Livelock {
    public static void main(String[] args) throws Exception {
        AtomicBoolean aWants = new AtomicBoolean(false);
        AtomicBoolean bWants = new AtomicBoolean(false);
        AtomicBoolean done   = new AtomicBoolean(false);

        Runnable a = () -> {
            while (!done.get()) {
                aWants.set(true);
                while (bWants.get()) {
                    aWants.set(false);
                    // burn cycles spinning while we "back off"
                    for (int i = 0; i < 1000; i++) Thread.onSpinWait();
                    aWants.set(true);
                }
                // both polite — never enters critical section
            }
        };
        Runnable b = () -> {
            while (!done.get()) {
                bWants.set(true);
                while (aWants.get()) {
                    bWants.set(false);
                    for (int i = 0; i < 1000; i++) Thread.onSpinWait();
                    bWants.set(true);
                }
            }
        };
        Thread t1 = new Thread(a, "livelock-a"); t1.setDaemon(true);
        Thread t2 = new Thread(b, "livelock-b"); t2.setDaemon(true);
        t1.start(); t2.start();
        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
