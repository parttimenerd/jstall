// CountDownLatch deadlock: no thread ever calls countDown() so all awaiters block.
import java.util.concurrent.*;

public class LatchDeadlock {
    public static void main(String[] args) throws Exception {
        CountDownLatch latch = new CountDownLatch(3);

        // 6 awaiters block on a latch that nobody decrements.
        for (int i = 0; i < 6; i++) {
            final int n = i;
            Thread t = new Thread(() -> {
                try { latch.await(); } catch (InterruptedException e) {}
            }, "latch-awaiter-" + n);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
