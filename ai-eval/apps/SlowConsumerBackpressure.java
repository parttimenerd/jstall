// Fast producer fills a bounded queue; producer parks when queue full (backpressure).
// slow-producer thread blocks on queue.put(); slow-consumer drains much more slowly.
import java.util.concurrent.*;
public class SlowConsumerBackpressure {
    public static void main(String[] args) throws Exception {
        BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(16);
        Thread producer = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { queue.put(new byte[1024]); } catch (Exception e) { break; }
            }
        }, "slow-producer");
        producer.setDaemon(true);
        Thread consumer = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    queue.take();
                    Thread.sleep(200); // very slow consumer
                } catch (Exception e) { break; }
            }
        }, "slow-consumer");
        consumer.setDaemon(true);
        producer.start();
        consumer.start();
        Thread.sleep(1000);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
