// H2 embedded DB: row-lock wait (not ABBA deadlock — H2 would resolve ABBA).
// tx1 holds row 1 locked in an open transaction; tx2 and tx3 both want row 1.
// LOCK_TIMEOUT=120000 means waiters block for 2 minutes — visible in thread dump.
import java.sql.*;
public class H2DeadlockTxn {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:h2:mem:txlock;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=120000";
        try (var c = DriverManager.getConnection(url)) {
            c.createStatement().execute("CREATE TABLE acct (id INT PRIMARY KEY, balance INT)");
            c.createStatement().execute("INSERT INTO acct VALUES (1, 1000)");
            c.createStatement().execute("INSERT INTO acct VALUES (2, 2000)");
        }

        // h2-txn-holder: acquires row lock on id=1 and holds it forever
        Thread holder = new Thread(() -> {
            try {
                var conn = DriverManager.getConnection(url);
                conn.setAutoCommit(false);
                conn.createStatement().execute("UPDATE acct SET balance=balance-100 WHERE id=1");
                Thread.sleep(Long.MAX_VALUE); // never commits — holds row lock
            } catch (Exception e) {}
        }, "h2-txn-holder");
        holder.setDaemon(true);
        holder.start();
        Thread.sleep(300); // ensure holder acquired lock first

        // h2-txn-waiter-1 and h2-txn-waiter-2: both try to update same locked row
        for (int i = 1; i <= 2; i++) {
            final int n = i;
            Thread waiter = new Thread(() -> {
                try {
                    var conn = DriverManager.getConnection(url);
                    conn.setAutoCommit(false);
                    // This will block until h2-txn-holder releases (never)
                    conn.createStatement().execute("UPDATE acct SET balance=balance+50 WHERE id=1");
                    conn.commit();
                } catch (Exception e) {}
            }, "h2-txn-waiter-" + n);
            waiter.setDaemon(true);
            waiter.start();
        }

        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
