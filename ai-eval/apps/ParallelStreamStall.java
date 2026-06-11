// Parallel stream tasks all lock on shared monitor -- ForkJoin common pool BLOCKED
import java.util.*;
import java.util.stream.*;
public class ParallelStreamStall {
    static final Object LOCK = new Object();
    static volatile boolean holder = true;
    public static void main(String[] args) throws Exception {
        // Grab the lock first so parallel stream tasks block
        Thread lockHolder = new Thread(() -> {
            synchronized(LOCK) { try { Thread.sleep(Long.MAX_VALUE); } catch (Exception e) {} }
        }, "pss-lock-holder");
        lockHolder.setDaemon(true); lockHolder.start();
        Thread.sleep(100);
        Thread streamRunner = new Thread(() -> {
            try {
                IntStream.range(0, 8).parallel().forEach(n -> {
                    Thread.currentThread().setName("pss-task-" + n);
                    synchronized(LOCK) { try { Thread.sleep(Long.MAX_VALUE); } catch (Exception e) {} }
                });
            } catch (Exception e) {}
        }, "pss-stream-runner");
        streamRunner.setDaemon(true); streamRunner.start();
        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
