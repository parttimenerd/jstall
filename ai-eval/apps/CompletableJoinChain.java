// Chain of CompletableFutures all blocking, last one never completes
import java.util.concurrent.*;
public class CompletableJoinChain {
    public static void main(String[] args) throws Exception {
        CompletableFuture<Void> blocker = new CompletableFuture<>();
        for (int i = 0; i < 8; i++) {
            final int n = i;
            final CompletableFuture<Void> f = blocker;
            Thread t = new Thread(() -> {
                try { f.join(); } catch (Exception e) {}
            }, "cjc-joiner-" + n);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
