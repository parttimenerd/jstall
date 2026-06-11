// ReferenceQueue.remove() blocks waiting for phantom references to be enqueued.
// phantom-ref-drainer thread WAITING in ReferenceQueue.remove() with no references pending.
import java.lang.ref.*;
public class PhantomRefStall {
    public static void main(String[] args) throws Exception {
        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        // Register a few phantom refs but hold strong refs so they're never collected
        Object[] strongRefs = new Object[5];
        PhantomReference<?>[] phantoms = new PhantomReference[5];
        for (int i = 0; i < 5; i++) {
            strongRefs[i] = new Object();
            phantoms[i] = new PhantomReference<>(strongRefs[i], queue);
        }
        Thread drainer = new Thread(() -> {
            try {
                while (true) {
                    queue.remove(60_000); // blocks — refs never enqueued (strong refs held)
                }
            } catch (Exception e) {}
        }, "phantom-ref-drainer");
        drainer.setDaemon(true);
        drainer.start();
        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
