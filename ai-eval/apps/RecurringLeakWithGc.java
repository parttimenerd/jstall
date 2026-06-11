// Slow leak: each request creates a large object retained in a static list. GC can't reclaim.
// Heap grows slowly but steadily — Δ is moderate, not explosive.
import java.util.*;

public class RecurringLeakWithGc {
    static final List<byte[]> leakList = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        Thread leaker = new Thread(() -> {
            while (true) {
                leakList.add(new byte[128 * 1024]); // 128 KB per "request"
                try { Thread.sleep(200); } catch (InterruptedException e) { return; }
            }
        }, "request-handler");
        leaker.setDaemon(true);
        leaker.start();
        Thread.sleep(2000);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
