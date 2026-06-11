// Soft reference cache evicted under heap pressure.
// sr-filler keeps adding SoftRef entries; GC evicts them when heap is tight.
import java.lang.ref.*;
import java.util.*;
public class SoftReferenceEviction {
    static final List<SoftReference<byte[]>> CACHE = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Thread filler = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                CACHE.add(new SoftReference<>(new byte[512 * 1024]));
                if (CACHE.size() > 2000) CACHE.subList(0, 100).clear();
                try { Thread.sleep(10); } catch (InterruptedException e) { break; }
            }
        }, "sr-filler");
        filler.setDaemon(true);
        filler.start();

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
