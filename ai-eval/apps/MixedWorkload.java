// Mixed CPU+IO workload, all healthy: threads doing real work, sleeping between batches.
// Should NOT be flagged as anything bad.
public class MixedWorkload {
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 3; i++) {
            final int id = i;
            Thread w = new Thread(() -> {
                while (true) {
                    // small CPU burst
                    long s = System.nanoTime();
                    while (System.nanoTime() - s < 2_000_000) {}
                    try { Thread.sleep(50); } catch (InterruptedException e) { return; }
                }
            }, "worker-" + id);
            w.setDaemon(true);
            w.start();
        }
        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
