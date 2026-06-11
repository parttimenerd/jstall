// Cache stampede: all threads try to populate the same cold entry simultaneously.
// cache-loader-N threads all BLOCKED on synchronized computeIfAbsent for same key.
import java.util.concurrent.*;
import java.util.*;
public class CachePopulateStall {
    static final Map<String, String> cache = new HashMap<>();
    static String slowLoad(String key) {
        try { Thread.sleep(60_000); } catch (Exception e) {} // very slow load
        return "value-" + key;
    }
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 10; i++) {
            final int n = i;
            Thread t = new Thread(() -> {
                synchronized (cache) {
                    if (!cache.containsKey("hot-key")) {
                        cache.put("hot-key", slowLoad("hot-key")); // all threads contend here
                    }
                }
            }, "cache-loader-" + n);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
