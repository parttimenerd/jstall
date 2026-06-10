// Heap allocation storm. Each allocator creates large arrays repeatedly, growing heap quickly.
// Should trigger heap-Δ > +5 MiB / 5s rule.
import java.util.*;
import java.util.concurrent.*;

public class HeapGrowth {
    static final List<byte[]> retained = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        Thread allocator = new Thread(() -> {
            while (true) {
                // ~2 MiB per iteration, retained → forces heap growth
                retained.add(new byte[2 * 1024 * 1024]);
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
        }, "allocator-1");
        allocator.setDaemon(true);
        allocator.start();
        Thread.sleep(2000); // let heap grow before signaling ready
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
