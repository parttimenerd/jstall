// CyclicBarrier broken: barrier party of 5 but only 4 ever arrive.
import java.util.concurrent.*;

public class BarrierStall {
    public static void main(String[] args) throws Exception {
        CyclicBarrier b = new CyclicBarrier(5);

        for (int i = 0; i < 4; i++) {
            final int n = i;
            Thread t = new Thread(() -> {
                try { b.await(); } catch (Exception e) {}
            }, "barrier-party-" + n);
            t.setDaemon(true);
            t.start();
        }
        // 5th party never arrives.

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
