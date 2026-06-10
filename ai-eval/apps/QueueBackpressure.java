// Producer blocked on a full bounded queue (backpressure). Consumer is slow.
import java.util.concurrent.*;

public class QueueBackpressure {
    public static void main(String[] args) throws Exception {
        ArrayBlockingQueue<Integer> q = new ArrayBlockingQueue<>(4);
        Thread producer = new Thread(() -> {
            int n = 0;
            while (true) {
                try { q.put(n++); } catch (InterruptedException e) { return; }
            }
        }, "producer-1");
        producer.setDaemon(true);
        producer.start();

        // Slow consumer - consumes once a second, much slower than producer
        Thread consumer = new Thread(() -> {
            while (true) {
                try {
                    Integer v = q.take();
                    Thread.sleep(1000);
                } catch (InterruptedException e) { return; }
            }
        }, "consumer-1");
        consumer.setDaemon(true);
        consumer.start();

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
