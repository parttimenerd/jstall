// ForkJoinTask calling Future.get() on another ForkJoin task — managed blocking
// should kick in but with enough nesting the pool stalls.
public class ForkJoinManagedBlock {
    static final java.util.concurrent.ForkJoinPool POOL = new java.util.concurrent.ForkJoinPool(2);

    public static void main(String[] args) throws Exception {
        // Submit a chain of tasks where each task submits another and blocks waiting for it.
        // With pool size 2 and 3 chained blocking tasks, pool threads are all blocked.
        for (int i = 0; i < 2; i++) {
            final int depth = i;
            POOL.submit(() -> {
                try {
                    // Each task submits a child task and blocks on get() — consumes all pool threads
                    java.util.concurrent.Future<Void> child = POOL.submit(() -> {
                        try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {}
                        return null;
                    });
                    child.get(); // blocks this thread inside the pool
                } catch (Exception e) {}
            });
        }

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
