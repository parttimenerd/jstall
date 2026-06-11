// JUnit Platform Console launcher running a deliberately-hanging test class.
// Mimics what you'd see if a CI test run was hung.
// We can't link Junit in eval/apps/ easily, so we approximate by mimicking the
// thread structure: ForkJoinPool.commonPool worker running a test method that hangs.
import java.util.concurrent.*;

public class JunitHang {
    static final Object DB = new Object();

    static void runTest(String name) {
        // emulate stuck integration test: hold a "DB lock" forever
        synchronized (DB) {
            try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {}
        }
    }

    public static void main(String[] args) throws Exception {
        // Threads named like JUnit Jupiter does: "ForkJoinPool-1-worker-N" + "junit-jupiter-N"
        // and "Test worker" — so the analyst can recognise it as a test framework.
        ExecutorService junit = new ForkJoinPool(4, pool -> {
            ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            t.setName("junit-jupiter-" + t.getPoolIndex());
            return t;
        }, null, false);

        // First test holds the lock and hangs.
        junit.submit(() -> runTest("HangingIntegrationTest.testStuckQuery"));

        Thread.sleep(50);

        // Sibling tests pile up trying to acquire DB lock.
        String[] tests = {
            "UserServiceTest.findUser",
            "OrderServiceTest.placeOrder",
            "AuthServiceTest.validateToken",
            "PaymentServiceTest.charge",
            "EmailServiceTest.send",
        };
        for (String n : tests) {
            junit.submit(() -> runTest(n));
        }

        // "Test worker" — Gradle's main test thread.
        Thread testWorker = new Thread(() -> {
            try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {}
        }, "Test worker");
        testWorker.setDaemon(true);
        testWorker.start();

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
