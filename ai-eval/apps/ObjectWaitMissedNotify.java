// Threads in Object.wait() that will never receive notify() — missed signal / lost wakeup.
public class ObjectWaitMissedNotify {
    static final Object lock = new Object();

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 5; i++) {
            final int id = i;
            Thread t = new Thread(() -> {
                synchronized (lock) {
                    try {
                        // Condition will never be true; nobody calls notify()
                        lock.wait();
                    } catch (InterruptedException e) {}
                }
            }, "waiter-" + id);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
