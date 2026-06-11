// 8 threads in tight CAS loop -- 100% CPU
import java.util.concurrent.atomic.*;
public class LockFreeSpin {
    static final AtomicLong counter = new AtomicLong(0);
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 8; i++) {
            Thread t = new Thread(() -> {
                long v;
                while (true) { v = counter.get(); counter.compareAndSet(v, v + 1); }
            }, "lfs-spinner-" + i);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
