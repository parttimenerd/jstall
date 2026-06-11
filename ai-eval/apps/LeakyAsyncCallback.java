// Async callback leak: CompletableFuture chains that are never completed
// hold references, preventing GC. Heap grows steadily.
import java.util.concurrent.*;
import java.util.*;
public class LeakyAsyncCallback {
    static final List<CompletableFuture<byte[]>> LEAKED = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Thread leaker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                // Create futures that are never completed — will never be GC'd
                var f = new CompletableFuture<byte[]>();
                f.thenApply(b -> new byte[b.length * 2]); // chained callback also leaked
                synchronized (LEAKED) { LEAKED.add(f); }
                try { Thread.sleep(20); } catch (InterruptedException e) { break; }
            }
        }, "async-leaker");
        leaker.setDaemon(true);
        leaker.start();

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
