// Busy-wait polling loop: thread spins checking a volatile flag with no sleep,
// but the flag is set by a thread that itself is stuck — double stall.
public class BusyWaitDoubleSpin {
    static volatile boolean flagA = false;
    static volatile boolean flagB = false;

    public static void main(String[] args) throws Exception {
        // Thread A: waits for flagA (set by B) — spins
        Thread a = new Thread(() -> {
            while (!flagA) Thread.onSpinWait();
        }, "busy-spin-a");

        // Thread B: waits for flagB (set by A) — but A is spinning, not setting B
        Thread b = new Thread(() -> {
            while (!flagB) Thread.onSpinWait();
            flagA = true; // would release A, but never reached
        }, "busy-spin-b");

        a.setDaemon(true); b.setDaemon(true);
        a.start(); b.start();

        Thread.sleep(400);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
