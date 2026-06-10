// Two threads, two locks, classic ABBA deadlock. JVM should report it via findDeadlockedThreads.
public class Deadlock {
    static final Object A = new Object();
    static final Object B = new Object();

    public static void main(String[] args) throws Exception {
        Thread t1 = new Thread(() -> {
            synchronized (A) {
                try { Thread.sleep(200); } catch (InterruptedException e) {}
                synchronized (B) { System.out.println("t1 done"); }
            }
        }, "abba-t1");
        Thread t2 = new Thread(() -> {
            synchronized (B) {
                try { Thread.sleep(200); } catch (InterruptedException e) {}
                synchronized (A) { System.out.println("t2 done"); }
            }
        }, "abba-t2");
        t1.start();
        t2.start();
        Thread.sleep(1500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        // Stay alive so jstall can sample
        Thread.sleep(120_000);
    }
}
