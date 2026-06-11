// HikariCP + H2: JDBC connection-level deadlock.
// Two threads each hold one connection and try to acquire a second
// while the pool is exactly size 2 — neither can proceed.
import com.zaxxer.hikari.*;
import java.sql.*;
public class HikariDeadlock {
    public static void main(String[] args) throws Exception {
        var cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:hikdeadlock;DB_CLOSE_DELAY=-1");
        cfg.setMaximumPoolSize(2);
        cfg.setConnectionTimeout(120_000);
        cfg.setPoolName("hik-deadlock-pool");
        var ds = new HikariDataSource(cfg);

        java.util.concurrent.CountDownLatch both = new java.util.concurrent.CountDownLatch(2);

        Thread t1 = new Thread(() -> {
            try {
                var c1 = ds.getConnection(); // takes slot 1
                both.countDown();
                both.await(); // wait for t2 to hold slot 2
                ds.getConnection(); // blocks — pool empty
            } catch (Exception e) {}
        }, "hik-dl-thread-1");

        Thread t2 = new Thread(() -> {
            try {
                var c2 = ds.getConnection(); // takes slot 2
                both.countDown();
                both.await();
                ds.getConnection(); // blocks — pool empty
            } catch (Exception e) {}
        }, "hik-dl-thread-2");

        t1.setDaemon(true); t2.setDaemon(true);
        t1.start(); t2.start();

        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
