// Simulate DB row locking: holder holds row lock, waiters block
public class DatabaseLockEmulation {
    static final Object ROW_1 = new Object(); // use plain Object, not boxed Long
    public static void main(String[] args) throws Exception {
        Thread holder = new Thread(() -> {
            synchronized(ROW_1) { try { Thread.sleep(Long.MAX_VALUE); } catch (Exception e) {} }
        }, "dble-holder");
        holder.setDaemon(true); holder.start();
        Thread.sleep(100);
        for (int i = 0; i < 8; i++) {
            final int n = i;
            Thread w = new Thread(() -> {
                synchronized(ROW_1) { System.out.println("got lock"); }
            }, "dble-waiter-" + n);
            w.setDaemon(true); w.start();
        }
        Thread.sleep(600);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
