// H2 embedded DB: a long-running JDBC query keeps CPU busy continuously.
// h2-slow-query runs a heavy cartesian-product query in a loop, always RUNNABLE.
import java.sql.*;
import java.util.concurrent.*;
public class H2LongQuery {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:h2:mem:longq;DB_CLOSE_DELAY=-1";
        try (var c = DriverManager.getConnection(url)) {
            c.createStatement().execute("CREATE TABLE big (id INT PRIMARY KEY)");
            c.setAutoCommit(false);
            for (int i = 0; i < 50_000; i++) c.createStatement().execute("INSERT INTO big VALUES (" + i + ")");
            c.commit();
        }

        // Long-query thread: repeating heavy cartesian join — stays RUNNABLE and CPU-hot
        Thread slow = new Thread(() -> {
            try {
                var conn = DriverManager.getConnection(url);
                while (!Thread.currentThread().isInterrupted()) {
                    // Cartesian join over 50k rows × 50k rows — always takes seconds on H2
                    conn.createStatement().executeQuery("SELECT COUNT(*) FROM big a, big b WHERE a.id < b.id AND b.id < 1000");
                }
            } catch (Exception e) {}
        }, "h2-slow-query");
        slow.setDaemon(true);
        slow.start();

        Thread.sleep(2000); // wait for query to be running
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
