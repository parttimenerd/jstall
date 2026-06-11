// Finalizer thread blocked because finalize() of pending objects holds a lock.
// Heap fills with un-collectable objects; "Finalizer" thread visible.
public class FinalizerStall {
    static final Object FIN_LOCK = new Object();
    static volatile boolean stallStarted = false;

    static class StuckFinalizer {
        @Override
        protected void finalize() {
            stallStarted = true;
            synchronized (FIN_LOCK) {
                try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {}
            }
        }
    }

    public static void main(String[] args) throws Exception {
        // Hold the finalizer lock from main so the finalize() body blocks.
        Thread holder = new Thread(() -> {
            synchronized (FIN_LOCK) {
                try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {}
            }
        }, "fin-lock-holder");
        holder.setDaemon(true);
        holder.start();
        Thread.sleep(50);

        // Allocate finalizable objects that pile up on the finalizer queue.
        for (int i = 0; i < 50_000; i++) {
            new StuckFinalizer();
            if ((i & 0xFFF) == 0) {
                System.gc();
            }
        }
        // Force GC to trigger finalize on the first one.
        System.gc();
        Thread.sleep(500);
        System.gc();

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
