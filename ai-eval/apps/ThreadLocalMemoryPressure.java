// ThreadLocal accumulation: short-lived threads each set a large ThreadLocal
// without calling remove() — classic leak pattern causing heap pressure.
public class ThreadLocalMemoryPressure {
    static final ThreadLocal<byte[]> CACHE = new ThreadLocal<>();

    public static void main(String[] args) throws Exception {
        Thread spawner = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                Thread t = new Thread(() -> {
                    CACHE.set(new byte[1024 * 1024]);
                    try { Thread.sleep(500); } catch (InterruptedException e) {}
                    // no CACHE.remove() — leak
                }, "tl-transient-" + System.nanoTime());
                t.setDaemon(true);
                t.start();
                try { Thread.sleep(50); } catch (InterruptedException e) { break; }
            }
        }, "tl-spawner");
        spawner.setDaemon(true);
        spawner.start();

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
