// 20 threads in exponential-backoff retry loops
public class NetworkRetryStorm {
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 20; i++) {
            final int n = i;
            Thread t = new Thread(() -> {
                long delay = 100 + (n * 37); // stagger retries
                while (true) {
                    try { Thread.sleep(delay); delay = Math.min(delay * 2, 30_000); }
                    catch (InterruptedException e) { break; }
                }
            }, "nrs-retrier-" + n);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
