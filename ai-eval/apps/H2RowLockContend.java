// H2 embedded DB: two threads fight over the same row in a long transaction.
// Thread h2-tx-writer holds a row lock; h2-tx-reader waits for it.
import java.sql.*;
public class H2RowLockContend {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:h2:mem:rowlock;DB_CLOSE_DELAY=-1";
        try (var c = DriverManager.getConnection(url)) {
            c.createStatement().execute("CREATE TABLE t (id INT PRIMARY KEY, val INT)");
            c.createStatement().execute("INSERT INTO t VALUES (1, 0)");
        }

        // Writer: hold open transaction with row lock
        Thread writer = new Thread(() -> {
            try {
                var conn = DriverManager.getConnection(url);
                conn.setAutoCommit(false);
                conn.createStatement().execute("UPDATE t SET val=99 WHERE id=1");
                Thread.sleep(Long.MAX_VALUE); // never commits
            } catch (Exception e) {}
        }, "h2-tx-writer");
        writer.setDaemon(true);
        writer.start();
        Thread.sleep(200);

        // 4 readers: block waiting for the same row
        for (int i = 0; i < 4; i++) {
            Thread reader = new Thread(() -> {
                try {
                    var conn = DriverManager.getConnection(url);
                    conn.setAutoCommit(false);
                    conn.createStatement().execute("UPDATE t SET val=1 WHERE id=1"); // blocks
                } catch (Exception e) {}
            }, "h2-tx-reader-" + i);
            reader.setDaemon(true);
            reader.start();
        }

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
