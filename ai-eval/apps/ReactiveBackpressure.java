// Reactive-style pipeline: slow subscriber causes unbounded upstream buffer growth.
// Publisher thread floods a queue; subscriber processes one item per second.
import java.util.concurrent.*;
public class ReactiveBackpressure {
    static final LinkedBlockingDeque<byte[]> BUFFER = new LinkedBlockingDeque<>(10_000);

    public static void main(String[] args) throws Exception {
        // Fast publisher
        Thread pub = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    BUFFER.put(new byte[1024]); // blocks when full
                } catch (InterruptedException e) { break; }
            }
        }, "rx-publisher");
        pub.setDaemon(true);

        // Slow subscriber
        Thread sub = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    BUFFER.take();
                    Thread.sleep(1000); // 1 item/sec
                } catch (InterruptedException e) { break; }
            }
        }, "rx-subscriber");
        sub.setDaemon(true);

        pub.start(); sub.start();
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
