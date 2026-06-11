// Many threads call future.get(timeout) on futures that never complete; all land in TIMED_WAITING.
// future-waiter-N threads stuck in Future.get() with long timeout.
import java.util.concurrent.*;
public class FutureGetTimeout {
    public static void main(String[] args) throws Exception {
        CompletableFuture<String> stuck = new CompletableFuture<>();
        for (int i = 0; i < 12; i++) {
            final int n = i;
            Thread t = new Thread(() -> {
                try { stuck.get(60, TimeUnit.SECONDS); } catch (Exception e) {}
            }, "future-waiter-" + n);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
