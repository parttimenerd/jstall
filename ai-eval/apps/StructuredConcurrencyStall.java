// StructuredTaskScope-style: virtual threads scoped to a task but the scope
// never closes because one subtask is stuck (Java 21 preview pattern).
import java.util.concurrent.*;
public class StructuredConcurrencyStall {
    public static void main(String[] args) throws Exception {
        // Simulate StructuredTaskScope: fork N subtasks, join waits for all
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var latch = new CountDownLatch(1); // represents the scope "join"

        // Fork 4 subtasks — one of them never finishes
        var futures = new java.util.ArrayList<Future<?>>();
        for (int i = 0; i < 3; i++) {
            futures.add(executor.submit(() -> {
                Thread.sleep(100); return "ok";
            }));
        }
        // The stuck subtask
        futures.add(executor.submit(() -> {
            Thread.currentThread().setName("scope-stuck-task");
            Thread.sleep(Long.MAX_VALUE); return "never";
        }));

        // Scope owner waits for all (like StructuredTaskScope.join())
        Thread scopeOwner = new Thread(() -> {
            try {
                for (var f : futures) f.get(); // blocks on stuck task
                latch.countDown();
            } catch (Exception e) {}
        }, "scope-owner");
        scopeOwner.setDaemon(true);
        scopeOwner.start();

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
