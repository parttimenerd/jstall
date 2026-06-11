// FixedThreadPool(4), all 4 workers sleeping forever
import java.util.concurrent.*;
public class ExecutorSaturated {
    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int i = 0; i < 4; i++) {
            final int n = i;
            pool.submit(() -> {
                Thread.currentThread().setName("es-worker-" + n);
                try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {}
            });
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
