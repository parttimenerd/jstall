// Resilience4j ThreadPoolBulkhead at capacity: maxConcurrentCalls=2,
// maxWaitDuration=120s. 2 tasks run forever, 6 callers wait in the bulkhead queue.
import io.github.resilience4j.bulkhead.*;
import java.util.concurrent.*;
public class Resilience4jBulkheadFull {
    public static void main(String[] args) throws Exception {
        var config = ThreadPoolBulkheadConfig.custom()
            .maxThreadPoolSize(2)
            .coreThreadPoolSize(2)
            .queueCapacity(10)
            .build();
        var bulkhead = ThreadPoolBulkhead.of("bh-pool", config);

        // 2 tasks that run forever — fill the pool
        for (int i = 0; i < 2; i++) {
            bulkhead.executeSupplier(() -> {
                try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {}
                return null;
            });
        }
        Thread.sleep(300);

        // 6 tasks waiting in the queue — will show as bulkhead-queue-N
        for (int i = 0; i < 6; i++) {
            final int n = i;
            new Thread(() -> {
                try {
                    ((java.util.concurrent.CompletableFuture<String>)
                        bulkhead.executeSupplier(() -> { return "done-" + n; })
                        .toCompletableFuture()).get();
                } catch (Exception e) {}
            }, "bh-caller-" + i).start();
        }

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
