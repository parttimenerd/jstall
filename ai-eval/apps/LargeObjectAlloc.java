// Threads allocating 10MB arrays periodically -- old gen pressure
import java.util.*;
public class LargeObjectAlloc {
    static final List<byte[]> REFS = Collections.synchronizedList(new ArrayList<>());
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    REFS.add(new byte[10 * 1024 * 1024]); // 10MB
                    if (REFS.size() > 20) REFS.subList(0, 10).clear();
                    try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                }
            }, "loa-allocator-" + i);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
