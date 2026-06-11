// JDBC connection leak pattern: a fixed pool of 3, 3 threads "forget" to close
// connections. Pool depleted; new requests time out.
import java.util.concurrent.*;
public class JdbcConnectionLeak {
    // Simulate a pool with Semaphore (like a real JDBC pool's internal counter)
    static final java.util.concurrent.Semaphore POOL = new java.util.concurrent.Semaphore(3);

    public static void main(String[] args) throws Exception {
        // 3 "leakers" acquire a connection and never release it
        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(() -> {
                try {
                    POOL.acquire(); // "opens" a connection
                    Thread.sleep(Long.MAX_VALUE); // never closes it
                } catch (InterruptedException e) {}
            }, "jdbc-leaker-" + i);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(200);

        // 6 honest callers wait for a connection that never returns
        for (int i = 0; i < 6; i++) {
            Thread t = new Thread(() -> {
                try {
                    POOL.acquire(); // blocks — pool empty
                    // Would use connection here
                    POOL.release();
                } catch (InterruptedException e) {}
            }, "jdbc-honest-caller-" + i);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
