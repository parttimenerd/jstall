// Threads aggressively allocating short-lived objects → high GC pressure.
// Should be flagged as allocation rate / heap churn even if no leak.
import java.util.*;

public class GcPressure {
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 4; i++) {
            final int id = i;
            Thread t = new Thread(() -> {
                while (true) {
                    // each iteration allocates ~1MB and discards it → churn
                    byte[] junk = new byte[1024 * 1024];
                    junk[0] = (byte) id;
                    if (junk[junk.length - 1] != 0) System.out.println(junk.length);
                }
            }, "alloc-churner-" + id);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
