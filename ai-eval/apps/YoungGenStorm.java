// Massive short-lived object allocation -- GC constantly running
public class YoungGenStorm {
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    byte[][] garbage = new byte[1000][];
                    for (int j = 0; j < 1000; j++) garbage[j] = new byte[1024];
                    // let it go out of scope immediately
                }
            }, "ygs-allocator-" + i);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
