// Producer/consumer where consumers drain faster than producer fills — consumers park idle.
// Should look healthy (idle consumers) not like backpressure.
import java.util.concurrent.*;

public class LinkedBlockingQueueDrain {
    public static void main(String[] args) throws Exception {
        LinkedBlockingQueue<Integer> q = new LinkedBlockingQueue<>(10_000);
        // Slow producer
        Thread prod = new Thread(() -> {
            int i = 0;
            while (true) {
                try { q.put(i++); Thread.sleep(100); } catch (InterruptedException e) { return; }
            }
        }, "lbq-producer");
        prod.setDaemon(true);
        prod.start();
        // Fast consumers — always idle waiting
        for (int c = 0; c < 4; c++) {
            Thread cons = new Thread(() -> {
                while (true) {
                    try { q.take(); } catch (InterruptedException e) { return; }
                }
            }, "lbq-consumer-" + c);
            cons.setDaemon(true);
            cons.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
