// Callback chain re-enqueues to executor — no forward progress despite activity.
// async-callback threads keep re-submitting to executor, CPU burns but work never finishes.
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public class AsyncCallbackLoop {
    static ExecutorService ex = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r); t.setDaemon(true);
        t.setName("async-callback-" + t.getId()); return t;
    });
    static AtomicLong counter = new AtomicLong();
    static void submit() {
        ex.submit(() -> {
            counter.incrementAndGet();
            submit(); // re-enqueue — infinite loop with no real progress
        });
    }
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 4; i++) submit();
        Thread.sleep(1500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
