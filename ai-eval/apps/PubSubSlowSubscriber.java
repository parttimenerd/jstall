// Slow consumer in a publish-subscribe: one fast publisher, 5 subscribers but
// one subscriber is very slow — all share a topic queue (backlog accumulates).
import java.util.concurrent.*;
import java.util.*;
public class PubSubSlowSubscriber {
    static final List<BlockingQueue<byte[]>> SUB_QUEUES = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        // Create 5 subscriber queues
        for (int i = 0; i < 5; i++) {
            SUB_QUEUES.add(new ArrayBlockingQueue<>(1000));
        }

        // Publisher: broadcasts to all subscriber queues
        Thread publisher = new Thread(() -> {
            byte[] msg = new byte[1024];
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    for (var q : SUB_QUEUES) q.put(msg);
                    Thread.sleep(10);
                } catch (InterruptedException e) { break; }
            }
        }, "pubsub-publisher");
        publisher.setDaemon(true);
        publisher.start();

        // 4 fast subscribers
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            Thread sub = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try { SUB_QUEUES.get(idx).take(); }
                    catch (InterruptedException e) { break; }
                }
            }, "pubsub-fast-sub-" + i);
            sub.setDaemon(true);
            sub.start();
        }

        // 1 slow subscriber — blocks publisher when its queue fills
        Thread slowSub = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    SUB_QUEUES.get(4).take();
                    Thread.sleep(2000); // processes 1 msg/2s
                } catch (InterruptedException e) { break; }
            }
        }, "pubsub-slow-sub");
        slowSub.setDaemon(true);
        slowSub.start();

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
