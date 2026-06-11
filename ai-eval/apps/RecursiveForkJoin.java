// ForkJoinTask tree that never finishes: recursive computation splits infinitely.
// Workers park in managedBlock, pool appears to have work but never completes.
import java.util.concurrent.*;
public class RecursiveForkJoin {
    static volatile boolean stop = false;

    static class InfiniteTask extends RecursiveAction {
        final int depth;
        InfiniteTask(int depth) { this.depth = depth; }
        protected void compute() {
            if (stop) return;
            if (depth > 10) {
                try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {}
                return;
            }
            invokeAll(new InfiniteTask(depth + 1), new InfiniteTask(depth + 1));
        }
    }

    public static void main(String[] args) throws Exception {
        var pool = new ForkJoinPool(4);
        pool.submit(new InfiniteTask(0));
        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
