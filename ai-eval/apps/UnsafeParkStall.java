// LockSupport.park() with no matching unpark — threads hung in WAITING.
// park-stall-N threads are parked with LockSupport.park() and never unparked.
import java.util.concurrent.locks.LockSupport;
public class UnsafeParkStall {
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 8; i++) {
            final int n = i;
            Thread t = new Thread(() -> {
                LockSupport.park(); // no blocker object, no unpark scheduled
            }, "park-stall-" + n);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
