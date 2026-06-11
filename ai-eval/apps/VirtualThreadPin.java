// Simulates virtual thread executor stall: platform threads named "vt-worker-N"
// all blocked on a semaphore with 0 permits — executor starved, nothing can proceed.
import java.util.concurrent.*;

public class VirtualThreadPin {
    public static void main(String[] args) throws Exception {
        Semaphore gate = new Semaphore(0); // no permits — all acquirers stall

        // 16 platform threads named vt-worker-N, all stuck waiting for semaphore permits
        for (int i = 0; i < 16; i++) {
            final int n = i;
            Thread t = new Thread(() -> {
                try { gate.acquire(); } catch (InterruptedException e) {}
            }, "vt-worker-" + n);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
