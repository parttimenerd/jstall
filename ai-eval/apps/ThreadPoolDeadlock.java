// Fixed pool size=1, outer task submits inner task and calls get() -- deadlock
import java.util.concurrent.*;
public class ThreadPoolDeadlock {
    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1, r -> {
            Thread t = new Thread(r, "tpd-worker");
            t.setDaemon(true); return t;
        });
        Thread outer = new Thread(() -> {
            try {
                Future<?> inner = pool.submit(() -> {
                    try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {}
                });
                inner.get(); // blocks tpd-worker which is also the only thread that could run inner
            } catch (Exception e) {}
        }, "tpd-outer");
        outer.setDaemon(true);
        pool.submit(outer::run);
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
