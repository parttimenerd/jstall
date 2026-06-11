// Off-heap direct ByteBuffer exhaustion: threads allocate direct memory
// until -XX:MaxDirectMemorySize is hit, then block on allocation.
import java.nio.ByteBuffer;
import java.util.*;
public class DirectBufferExhaust {
    static final List<ByteBuffer> HELD = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        // Allocate 256MB chunks until we approach the direct memory limit
        Thread allocator = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    HELD.add(ByteBuffer.allocateDirect(64 * 1024 * 1024)); // 64MB each
                    Thread.sleep(200);
                } catch (OutOfMemoryError e) {
                    break; // direct memory exhausted
                } catch (InterruptedException e) { break; }
            }
        }, "direct-allocator");
        allocator.setDaemon(true);
        allocator.start();
        Thread.sleep(400);

        // Workers that also need direct buffers — will block on GC trying to free
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        ByteBuffer.allocateDirect(1024 * 1024); // 1MB
                    } catch (OutOfMemoryError e) {
                        try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                    }
                }
            }, "direct-buffer-user-" + i);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
