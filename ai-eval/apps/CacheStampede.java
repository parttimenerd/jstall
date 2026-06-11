// Cache stampede: many threads simultaneously miss a shared cache entry,
// all try to recompute it under a lock — thundering herd on one key.
import java.util.concurrent.*;
public class CacheStampede {
    static final ConcurrentHashMap<String, byte[]> CACHE = new ConcurrentHashMap<>();
    static final Object COMPUTE_LOCK = new Object();

    static byte[] expensiveCompute() {
        try { Thread.sleep(5000); } catch (InterruptedException e) {}
        return new byte[1024];
    }

    public static void main(String[] args) throws Exception {
        // Expire the single cache entry periodically
        Thread expirer = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                CACHE.clear();
            }
        }, "cache-expirer");
        expirer.setDaemon(true);
        expirer.start();

        // 15 threads race to serve "hot-key"
        for (int i = 0; i < 15; i++) {
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    if (CACHE.containsKey("hot-key")) { CACHE.get("hot-key"); continue; }
                    synchronized (COMPUTE_LOCK) {
                        if (!CACHE.containsKey("hot-key"))
                            CACHE.put("hot-key", expensiveCompute()); // one at a time, others wait
                    }
                }
            }, "cache-stampeder-" + i);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
