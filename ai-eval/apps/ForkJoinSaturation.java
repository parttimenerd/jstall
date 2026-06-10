// ForkJoinPool saturation: submit deep recursive tasks that fill the common pool.
import java.util.concurrent.*;

public class ForkJoinSaturation {
    static class Recurse extends RecursiveTask<Long> {
        final int depth;
        Recurse(int d) { this.depth = d; }
        @Override protected Long compute() {
            if (depth <= 0) {
                long s = System.nanoTime();
                while (System.nanoTime() - s < 5_000_000) {}
                return 1L;
            }
            Recurse a = new Recurse(depth - 1);
            Recurse b = new Recurse(depth - 1);
            a.fork(); b.fork();
            return a.join() + b.join();
        }
    }
    public static void main(String[] args) throws Exception {
        // Submit many parallel deep tasks
        for (int i = 0; i < 4; i++) {
            final int id = i;
            Thread submitter = new Thread(() -> {
                while (true) {
                    new Recurse(8).invoke();
                }
            }, "fj-submitter-" + id);
            submitter.setDaemon(true);
            submitter.start();
        }
        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
