// CyclicBarrier with one thread that throws: barrier is "broken" —
// all other parties get BrokenBarrierException and spin trying to reset.
import java.util.concurrent.*;
public class BrokenBarrier {
    public static void main(String[] args) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(5);

        // 4 normal parties
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        barrier.await();
                    } catch (BrokenBarrierException e) {
                        // Barrier is broken — keep retrying (spin)
                        try { Thread.sleep(100); } catch (InterruptedException ie) { break; }
                    } catch (InterruptedException e) { break; }
                }
            }, "bb-party-" + i);
            t.setDaemon(true);
            t.start();
        }

        // 5th party: throws an exception, breaking the barrier
        Thread breaker = new Thread(() -> {
            try {
                barrier.await(100, TimeUnit.MILLISECONDS); // times out → breaks barrier
            } catch (Exception e) {} // barrier is now broken
            // Never calls barrier.reset() — all other parties stuck
            try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {}
        }, "bb-breaker");
        breaker.setDaemon(true);
        breaker.start();

        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
