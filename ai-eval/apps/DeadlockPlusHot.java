// Two issues at once: ABBA deadlock AND a separate hot CPU loop. Both should be reported.
public class DeadlockPlusHot {
    static final Object A = new Object();
    static final Object B = new Object();

    public static void main(String[] args) throws Exception {
        Thread t1 = new Thread(() -> {
            synchronized (A) {
                try { Thread.sleep(200); } catch (InterruptedException e) {}
                synchronized (B) {}
            }
        }, "abba-t1");
        Thread t2 = new Thread(() -> {
            synchronized (B) {
                try { Thread.sleep(200); } catch (InterruptedException e) {}
                synchronized (A) {}
            }
        }, "abba-t2");
        Thread spinner = new Thread(() -> {
            long x = 0;
            while (true) { x += System.nanoTime() ^ (x * 31); if ((x & 0x7fffffff) == 0) System.out.println(x); }
        }, "cpu-burner");
        spinner.setDaemon(true);
        t1.start(); t2.start(); spinner.start();
        Thread.sleep(1500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
