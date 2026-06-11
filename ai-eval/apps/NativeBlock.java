// JNI / native call stuck: thread inside a synchronized block calling a native method
// that never returns (we approximate with park forever from native context).
// In practice we use Object.wait without notify in a synchronized block.
public class NativeBlock {
    static final Object MUTEX = new Object();

    public static void main(String[] args) throws Exception {
        // Thread held in Object.wait — native park.
        Thread t = new Thread(() -> {
            synchronized (MUTEX) {
                try { MUTEX.wait(); } catch (InterruptedException e) {}
            }
        }, "native-waiter");
        t.setDaemon(true);
        t.start();

        // 3 more BLOCKED waiting for MUTEX (since the waiter holds it... no, wait() releases).
        // Instead we have one thread holding the monitor forever, the others blocked.
        Object holdingLock = new Object();
        Thread holder = new Thread(() -> {
            synchronized (holdingLock) {
                try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {}
            }
        }, "native-holder");
        holder.setDaemon(true);
        holder.start();
        Thread.sleep(50);

        for (int i = 0; i < 4; i++) {
            final int n = i;
            Thread w = new Thread(() -> {
                synchronized (holdingLock) {
                    System.out.println("got " + n);
                }
            }, "native-blocked-" + n);
            w.setDaemon(true);
            w.start();
        }

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
