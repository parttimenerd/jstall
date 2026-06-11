// Thread uses wait() without a loop — wakes spuriously and misinterprets as signal.
// spurious-wait thread stuck in Object.wait() because it never re-checks the condition.
public class ObjectWaitSpurious {
    static final Object monitor = new Object();
    static volatile boolean ready = false;
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 5; i++) {
            final int n = i;
            Thread t = new Thread(() -> {
                synchronized (monitor) {
                    try {
                        monitor.wait(60_000); // no loop — spurious wakeup would exit
                        // After wakeup, does no condition check — bad pattern
                        Thread.sleep(Long.MAX_VALUE);
                    } catch (Exception e) {}
                }
            }, "spurious-wait-" + n);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
