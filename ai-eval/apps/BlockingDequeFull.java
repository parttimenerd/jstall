// Threads blocked on putFirst() on a full LinkedBlockingDeque
import java.util.concurrent.*;
public class BlockingDequeFull {
    static final LinkedBlockingDeque<Integer> DEQUE = new LinkedBlockingDeque<>(5);
    public static void main(String[] args) throws Exception {
        // Fill the deque
        for (int i = 0; i < 5; i++) DEQUE.put(i);
        // Now threads try to add more -- block
        for (int i = 0; i < 12; i++) {
            Thread t = new Thread(() -> {
                try { DEQUE.putFirst(99); } catch (InterruptedException e) {}
            }, "bdf-putter-" + i);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
