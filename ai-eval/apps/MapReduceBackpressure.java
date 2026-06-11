// Map reduce-style job: mapper threads produce to a fixed-size channel,
// reducer is slower — mappers block on channel put (backpressure).
import java.util.concurrent.*;
public class MapReduceBackpressure {
    static final ArrayBlockingQueue<int[]> CHANNEL = new ArrayBlockingQueue<>(5);

    public static void main(String[] args) throws Exception {
        // Reducer: very slow, processes one chunk per 3 seconds
        Thread reducer = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    int[] chunk = CHANNEL.take();
                    long sum = 0;
                    for (int v : chunk) sum += v;
                    Thread.sleep(3000);
                } catch (InterruptedException e) { break; }
            }
        }, "mr-reducer");
        reducer.setDaemon(true);
        reducer.start();

        // 6 mappers: fast, block when channel fills up
        for (int i = 0; i < 6; i++) {
            Thread mapper = new Thread(() -> {
                int[] chunk = new int[1000];
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        CHANNEL.put(chunk); // blocks when reducer is slow
                    } catch (InterruptedException e) { break; }
                }
            }, "mr-mapper-" + i);
            mapper.setDaemon(true);
            mapper.start();
        }

        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
