// Producers blocking on LinkedTransferQueue.transfer() -- no consumer
import java.util.concurrent.*;
public class LinkedTransferStall {
    static final LinkedTransferQueue<Integer> Q = new LinkedTransferQueue<>();
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 10; i++) {
            Thread t = new Thread(() -> {
                try { Q.transfer(42); } catch (InterruptedException e) {}
            }, "lts-producer-" + i);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
