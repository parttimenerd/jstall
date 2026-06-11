// HikariCP connection pool exhausted: maximumPoolSize=3, 6 borrowers each hold
// a connection sleeping. Pool reports BLOCKED/waiting threads.
import com.zaxxer.hikari.*;
import java.sql.*;
public class HikariPoolExhaust {
    public static void main(String[] args) throws Exception {
        var cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:hikari;DB_CLOSE_DELAY=-1");
        cfg.setMaximumPoolSize(3);
        cfg.setConnectionTimeout(120_000);
        cfg.setPoolName("hikari-pool");
        var ds = new HikariDataSource(cfg);

        // 3 holders grab all connections and sleep
        for (int i = 0; i < 3; i++) {
            final int n = i;
            Thread t = new Thread(() -> {
                try {
                    var conn = ds.getConnection(); // grabs a slot
                    Thread.sleep(Long.MAX_VALUE);
                } catch (Exception e) {}
            }, "hikari-holder-" + n);
            t.setDaemon(true); t.start();
        }
        Thread.sleep(200);

        // 5 waiters pile up trying to borrow
        for (int i = 0; i < 5; i++) {
            Thread t = new Thread(() -> {
                try { ds.getConnection(); } catch (Exception e) {}
            }, "hikari-waiter-" + i);
            t.setDaemon(true); t.start();
        }

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
