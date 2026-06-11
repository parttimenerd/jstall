// invokeAll with short timeout, workers all sleeping past deadline
import java.util.concurrent.*;
import java.util.*;
public class BulkTimeoutStall {
    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            final int n = i;
            tasks.add(() -> {
                Thread.currentThread().setName("bts-worker-" + n);
                Thread.sleep(Long.MAX_VALUE);
                return null;
            });
        }
        // Submit tasks that run indefinitely
        for (int i = 0; i < 6; i++) {
            final int n = i;
            pool.submit(() -> {
                Thread.currentThread().setName("bts-worker-" + n);
                try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {}
            });
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
