// SynchronousQueue handoff stall: producer can't hand off because no consumer is matched.
// Both sides park in SynchronousQueue.transfer.
import java.util.concurrent.*;

public class SyncQueueStall {
    public static void main(String[] args) throws Exception {
        SynchronousQueue<Integer> q = new SynchronousQueue<>();
        // Only producers, no consumers. Each producer parks waiting for a taker.
        for (int i = 0; i < 6; i++) {
            final int id = i;
            Thread p = new Thread(() -> {
                try { q.put(id); } catch (InterruptedException e) {}
            }, "sq-producer-" + id);
            p.setDaemon(true);
            p.start();
        }
        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
