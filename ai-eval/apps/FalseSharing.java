// False sharing: multiple threads update adjacent fields in the same cache line,
// causing cache-line bouncing — appears as high CPU with no real work done.
public class FalseSharing {
    // Padding to force adjacent fields into same cache line
    static long[] SHARED = new long[16]; // 8 bytes × 16 = 128 bytes, 2 cache lines
    // Threads 0-3 write indices 0,1,2,3 — all in the same 64-byte cache line

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 4; i++) {
            final int idx = i; // indices 0-3 share a cache line
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    SHARED[idx]++; // false sharing: adjacent to other threads' updates
                }
            }, "false-share-writer-" + i);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(400);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
