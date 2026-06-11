// Many objects with finalize() overflow the finalizer queue.
// Finalizer thread falls behind; allocation thread keeps producing.
public class GcFinalizerBacklog {
    @SuppressWarnings("deprecation")
    static class Garbage {
        byte[] payload = new byte[4096];
        @Override protected void finalize() {
            try { Thread.sleep(1); } catch (Exception e) {} // slow finalizer
        }
    }
    public static void main(String[] args) throws Exception {
        Thread alloc = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                Garbage g = new Garbage();
            }
        }, "finalizer-alloc");
        alloc.setDaemon(true);
        alloc.start();
        Thread.sleep(2000);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
