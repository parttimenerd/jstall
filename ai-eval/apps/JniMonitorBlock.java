// JNI-style blocked thread: thread calls a "native" method that holds a monitor.
// Simulates native code holding a Java monitor — appears RUNNABLE with native frame.
public class JniMonitorBlock {
    static final Object JNI_LOCK = new Object();

    public static void main(String[] args) throws Exception {
        // Simulate a "native" holder: takes the lock and spins (like a JNI call holding the monitor)
        Thread holder = new Thread(() -> {
            synchronized (JNI_LOCK) {
                // Simulate native code running while holding the monitor
                long start = System.nanoTime();
                while (System.nanoTime() - start < Long.MAX_VALUE) {
                    Thread.onSpinWait();
                }
            }
        }, "jni-native-holder");
        holder.setDaemon(true);
        holder.start();
        Thread.sleep(100);

        // 6 Java threads BLOCKED waiting for the JNI-held monitor
        for (int i = 0; i < 6; i++) {
            Thread t = new Thread(() -> {
                synchronized (JNI_LOCK) { /* never reached */ }
            }, "jni-waiter-" + i);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(400);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
