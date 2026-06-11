// .orTimeout() chains: each timed-out future spawns another future with orTimeout(), growing chain.
// cf-timeout-chain threads stuck in CompletableFuture.get() with nested timeout chains.
import java.util.concurrent.*;
public class CompletableTimeoutChain {
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 8; i++) {
            final int n = i;
            Thread t = new Thread(() -> {
                try {
                    // Chain of futures each timing out and creating another
                    CompletableFuture<String> f = new CompletableFuture<String>()
                        .orTimeout(60, TimeUnit.SECONDS);
                    f.exceptionally(ex -> {
                        // On timeout, create another stuck future — chain grows
                        return new CompletableFuture<String>()
                            .orTimeout(60, TimeUnit.SECONDS).join();
                    }).get(90, TimeUnit.SECONDS);
                } catch (Exception e) {}
            }, "cf-timeout-chain-" + n);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
