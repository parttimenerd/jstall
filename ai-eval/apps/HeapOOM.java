// App racing toward OOM: heap used >80%, GC spinning, allocation rate very high.
import java.util.*;

public class HeapOOM {
    static final List<byte[]> sink = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        long maxMem = Runtime.getRuntime().maxMemory();
        long targetBytes = (long)(maxMem * 0.82);  // fill to ~82%

        // Fill heap rapidly to 82%
        while (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory() < targetBytes) {
            sink.add(new byte[512 * 1024]);  // 512 KB chunks
        }

        // Keep allocating small objects to keep GC busy (pressure but not OOM)
        Thread allocator = new Thread(() -> {
            List<byte[]> churn = new ArrayList<>();
            while (true) {
                churn.add(new byte[64 * 1024]);
                if (churn.size() > 20) churn.subList(0, 10).clear();
                try { Thread.sleep(10); } catch (InterruptedException e) { return; }
            }
        }, "oom-allocator");
        allocator.setDaemon(true);
        allocator.start();

        Thread.sleep(1000);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
