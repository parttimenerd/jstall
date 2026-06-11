// CompletableFuture chain stall: futures never complete because the upstream
// future is parked forever. Many CF worker threads waiting.
import java.util.concurrent.*;

public class CompletableChain {
    public static void main(String[] args) throws Exception {
        CompletableFuture<String> upstream = new CompletableFuture<>();

        // 8 downstream stages waiting on upstream.
        for (int i = 0; i < 8; i++) {
            final int n = i;
            CompletableFuture<String> chain = upstream
                .thenApplyAsync(v -> v + " step1-" + n)
                .thenApplyAsync(v -> v + " step2-" + n);
            // Block a thread on the chain to pin it.
            Thread t = new Thread(() -> {
                try { chain.get(); } catch (Exception e) {}
            }, "cf-waiter-" + n);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
