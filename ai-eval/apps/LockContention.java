// 8 threads all hammering the same synchronized block. Heavy lock contention but no deadlock.
public class LockContention {
    static final Object LOCK = new Object();
    static volatile long counter = 0;

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 8; i++) {
            final int id = i;
            Thread t = new Thread(() -> {
                while (true) {
                    synchronized (LOCK) {
                        // hold the lock for a non-trivial time to maximize contention
                        long start = System.nanoTime();
                        while (System.nanoTime() - start < 5_000_000) {
                            counter++;
                        }
                    }
                }
            }, "contender-" + id);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
