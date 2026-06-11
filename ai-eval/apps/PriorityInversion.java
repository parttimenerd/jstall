// Classic priority inversion: low-priority thread holds a lock that a high-priority thread needs,
// while medium-priority threads starve the low-priority one.
public class PriorityInversion {
    static final Object sharedLock = new Object();

    public static void main(String[] args) throws Exception {
        // Low-priority thread acquires the lock then does "work"
        Thread low = new Thread(() -> {
            synchronized (sharedLock) {
                // Does slow work while holding the lock
                long end = System.nanoTime() + 120_000_000_000L;
                while (System.nanoTime() < end) {
                    Thread.yield();
                }
            }
        }, "low-prio-holder");
        low.setPriority(Thread.MIN_PRIORITY);
        low.setDaemon(true);
        low.start();

        Thread.sleep(100);

        // Medium-priority CPU burners starve the low-priority holder
        for (int i = 0; i < 3; i++) {
            Thread m = new Thread(() -> {
                while (true) Thread.yield();
            }, "med-prio-burner-" + i);
            m.setPriority(Thread.NORM_PRIORITY);
            m.setDaemon(true);
            m.start();
        }

        // High-priority thread needs the lock — stuck waiting
        Thread high = new Thread(() -> {
            synchronized (sharedLock) {
                System.out.println("high-prio acquired lock");
            }
        }, "high-prio-waiter");
        high.setPriority(Thread.MAX_PRIORITY);
        high.setDaemon(true);
        high.start();

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
