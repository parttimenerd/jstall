// High object survival rate forces frequent full GCs — GC thrashing.
// Objects survive long enough to promote to old-gen, keeping GC busy.
public class GcThrash {
    static final java.util.Deque<byte[]> SURVIVOR = new java.util.ArrayDeque<>();

    public static void main(String[] args) throws Exception {
        Thread thrash = new Thread(() -> {
            int epoch = 0;
            while (!Thread.currentThread().isInterrupted()) {
                // Add 4MB per epoch, evict chunks from 3 epochs ago → 75% survival rate
                for (int i = 0; i < 4; i++) {
                    SURVIVOR.addLast(new byte[1024 * 1024]);
                }
                if (SURVIVOR.size() > 12) {
                    SURVIVOR.removeFirst();
                }
                epoch++;
                System.gc();
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
        }, "gc-thrash-driver");
        thrash.setDaemon(true);
        thrash.start();

        Thread.sleep(1000);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
