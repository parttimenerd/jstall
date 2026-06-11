// HikariCP-like pool at capacity; threads waiting for a connection lease.
// conn-lease-waiter-N threads blocked waiting for pool to release a connection.
import java.util.concurrent.*;
public class ConnectionLeaseStall {
    static final int POOL_SIZE = 3;
    static final Semaphore pool = new Semaphore(POOL_SIZE);
    public static void main(String[] args) throws Exception {
        // Holders: acquire all connections and never release
        for (int i = 0; i < POOL_SIZE; i++) {
            final int n = i;
            Thread holder = new Thread(() -> {
                try {
                    pool.acquire();
                    Thread.sleep(Long.MAX_VALUE); // holds connection forever
                } catch (Exception e) {}
            }, "conn-holder-" + n);
            holder.setDaemon(true);
            holder.start();
        }
        Thread.sleep(100);
        // Waiters: request a connection — will be queued
        for (int i = 0; i < 9; i++) {
            final int n = i;
            Thread waiter = new Thread(() -> {
                try { pool.acquire(); } catch (Exception e) {}
            }, "conn-lease-waiter-" + n);
            waiter.setDaemon(true);
            waiter.start();
        }
        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
