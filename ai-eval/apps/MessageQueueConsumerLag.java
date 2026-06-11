// Simulates a Kafka-style consumer lag: single consumer thread processes
// messages much slower than they arrive. Queue depth grows without bound.
import java.util.concurrent.*;
public class MessageQueueConsumerLag {
    static final LinkedBlockingQueue<byte[]> QUEUE = new LinkedBlockingQueue<>();

    public static void main(String[] args) throws Exception {
        // Producer: emit 100 messages/sec
        Thread producer = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    QUEUE.put(new byte[1024]);
                    Thread.sleep(10);
                } catch (InterruptedException e) { break; }
            }
        }, "mq-producer");
        producer.setDaemon(true);
        producer.start();

        // Consumer: processes 1 message/sec — 100x slower than producer
        Thread consumer = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    QUEUE.take();
                    Thread.sleep(1000);
                } catch (InterruptedException e) { break; }
            }
        }, "mq-consumer");
        consumer.setDaemon(true);
        consumer.start();

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
