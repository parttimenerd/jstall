// Direct ByteBuffer allocation without releasing -- off-heap grows
import java.nio.ByteBuffer;
import java.util.*;
public class OffHeapLeak {
    static final List<ByteBuffer> LEAK = new ArrayList<>();
    public static void main(String[] args) throws Exception {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    LEAK.add(ByteBuffer.allocateDirect(1024 * 1024)); // 1MB each
                    Thread.sleep(50);
                } catch (Exception e) { break; }
            }
        }, "ohl-allocator");
        t.setDaemon(true); t.start();
        Thread.sleep(1000);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
