// 50 threads all sleeping for 1-second intervals -- thundering herd on resume
public class RateLimitedHerd {
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 50; i++) {
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                }
            }, "rlh-thread-" + i);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
