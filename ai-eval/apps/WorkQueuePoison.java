// SynchronousQueue: producers block trying to hand off, consumer drains and exits
import java.util.concurrent.*;
public class WorkQueuePoison {
    public static void main(String[] args) throws Exception {
        SynchronousQueue<Integer> queue = new SynchronousQueue<>();
        for (int i = 0; i < 8; i++) {
            final int n = i;
            Thread t = new Thread(() -> {
                try { queue.put(n); } catch (InterruptedException e) {}
            }, "wqp-producer-" + n);
            t.setDaemon(true); t.start();
        }
        Thread consumer = new Thread(() -> {
            try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException e) {}
        }, "wqp-consumer");
        consumer.setDaemon(true); consumer.start();
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
