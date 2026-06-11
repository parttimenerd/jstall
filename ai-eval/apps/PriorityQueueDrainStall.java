// 20 threads blocked on PriorityBlockingQueue.take() -- queue empty, no producer
import java.util.concurrent.*;
public class PriorityQueueDrainStall {
    static final PriorityBlockingQueue<Integer> Q = new PriorityBlockingQueue<>();
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 20; i++) {
            Thread t = new Thread(() -> {
                try { Q.take(); } catch (InterruptedException e) {}
            }, "pqd-consumer-" + i);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
