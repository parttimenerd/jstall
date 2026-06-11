// CompletableFuture.allOf() waiting on futures that never complete.
// cf-allof-waiter thread stuck in allOf().get(), cf-task-N futures never resolved.
import java.util.concurrent.*;
public class CompletableAllOfStall {
    public static void main(String[] args) throws Exception {
        CompletableFuture<?>[] futures = new CompletableFuture[8];
        for (int i = 0; i < 8; i++) {
            futures[i] = new CompletableFuture<>(); // never completed
        }
        Thread waiter = new Thread(() -> {
            try {
                CompletableFuture.allOf(futures).get(); // blocks forever
            } catch (Exception e) {}
        }, "cf-allof-waiter");
        waiter.setDaemon(true);
        waiter.start();
        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
