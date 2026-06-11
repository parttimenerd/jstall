// Many threads hammering the same ConcurrentHashMap segment — monitor contention.
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ConcurrentHashMapContention {
    static final ConcurrentHashMap<Integer, AtomicLong> map = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        // Pre-populate to create segment contention on a small key range
        for (int i = 0; i < 4; i++) map.put(i, new AtomicLong(0));

        for (int i = 0; i < 20; i++) {
            Thread t = new Thread(() -> {
                while (true) {
                    // compute() on same keys causes stripe lock contention
                    map.compute(0, (k, v) -> { v.incrementAndGet(); return v; });
                    map.compute(1, (k, v) -> { v.incrementAndGet(); return v; });
                }
            }, "chm-contender-" + i);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
