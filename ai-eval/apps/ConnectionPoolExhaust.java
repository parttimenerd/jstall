// All "DB connections" from a fixed pool are held by slow queries — new requests park.
import java.util.concurrent.*;

public class ConnectionPoolExhaust {
    static final Semaphore connPool = new Semaphore(3); // pool of 3

    public static void main(String[] args) throws Exception {
        // 3 "slow queries" hold all connections
        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(() -> {
                try {
                    connPool.acquire();
                    Thread.sleep(120_000); // holds connection
                } catch (InterruptedException e) {}
                finally { connPool.release(); }
            }, "db-slow-query-" + i);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(300);
        // 6 "new requests" can't get a connection
        for (int i = 0; i < 6; i++) {
            Thread t = new Thread(() -> {
                try {
                    connPool.acquire(); // parks here
                    Thread.sleep(1000);
                } catch (InterruptedException e) {}
                finally { connPool.release(); }
            }, "db-waiting-req-" + i);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
