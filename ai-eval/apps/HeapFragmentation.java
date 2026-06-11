// Heap fragmentation: many small, medium, and large objects allocated in phases,
// causing fragmentation that prevents large allocations despite apparent free space.
import java.util.*;
public class HeapFragmentation {
    static final List<Object> LIVE = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Thread fragmenter = new Thread(() -> {
            Random rng = new Random(42);
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // Alternate between small and large allocations to fragment
                    if (rng.nextBoolean()) {
                        LIVE.add(new byte[rng.nextInt(512) + 16]); // tiny: 16-512B
                    } else {
                        LIVE.add(new byte[rng.nextInt(65536) + 8192]); // medium: 8-64KB
                    }
                    if (LIVE.size() > 50_000) {
                        // Remove every other small object — leaves gaps
                        for (int i = LIVE.size() - 1; i >= 0; i -= 2) LIVE.remove(i);
                    }
                    // Occasionally try a large allocation
                    if (rng.nextInt(100) == 0) LIVE.add(new byte[4 * 1024 * 1024]);
                } catch (OutOfMemoryError e) {
                    LIVE.subList(0, LIVE.size() / 2).clear();
                }
            }
        }, "heap-fragmenter");
        fragmenter.setDaemon(true);
        fragmenter.start();

        Thread.sleep(1000);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
