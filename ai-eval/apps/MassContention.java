// Many-threads contention: 30 threads all hammer the same `synchronized` method.
// Larger scale than LockContention; thread-count-warning may also fire.
// Should be flagged as severe synchronized contention.
public class MassContention {
    static final Object MUTEX = new Object();

    static void hot() {
        synchronized (MUTEX) {
            long start = System.nanoTime();
            // hold for ~3ms
            while (System.nanoTime() - start < 3_000_000) {}
        }
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 30; i++) {
            final int id = i;
            Thread t = new Thread(() -> {
                while (true) hot();
            }, "mass-contender-" + id);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
