// ThreadLocal holding large byte arrays; threads never clean up — accumulates per-thread data.
public class ThreadLocalLeak {
    static final ThreadLocal<byte[]> LOCAL = new ThreadLocal<>();
    static final java.util.List<Thread> THREADS = new java.util.ArrayList<>();

    public static void main(String[] args) throws Exception {
        // Spawn 50 threads that each set a 500KB ThreadLocal and then sleep forever
        for (int i = 0; i < 50; i++) {
            Thread t = new Thread(() -> {
                LOCAL.set(new byte[512 * 1024]); // 512KB per thread
                try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {}
            }, "tl-holder-" + i);
            t.setDaemon(true);
            t.start();
            synchronized (THREADS) { THREADS.add(t); }
        }

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
