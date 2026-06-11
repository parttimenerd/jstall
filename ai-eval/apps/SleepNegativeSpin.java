// Thread.sleep(0) in tight loop — busy-wait that yields but never blocks.
// sleep-zero-spin thread stays RUNNABLE burning CPU with sleep(0) calls.
public class SleepNegativeSpin {
    public static void main(String[] args) throws Exception {
        Thread t = new Thread(() -> {
            long counter = 0;
            while (!Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(0); } catch (Exception e) { break; } // sleep(0) = yield
                counter++;
            }
        }, "sleep-zero-spin");
        t.setDaemon(true);
        t.start();
        Thread.sleep(1000);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
