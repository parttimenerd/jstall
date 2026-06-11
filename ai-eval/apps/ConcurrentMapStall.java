// Threads stalled in ConcurrentHashMap.compute() with slow function
import java.util.concurrent.*;
public class ConcurrentMapStall {
    static final ConcurrentHashMap<String, Integer> MAP = new ConcurrentHashMap<>();
    public static void main(String[] args) throws Exception {
        MAP.put("key", 0);
        for (int i = 0; i < 16; i++) {
            Thread t = new Thread(() -> {
                MAP.compute("key", (k, v) -> {
                    try { Thread.sleep(Long.MAX_VALUE); } catch (Exception e) {}
                    return (v == null ? 0 : v) + 1;
                });
            }, "cms-compute-" + i);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
