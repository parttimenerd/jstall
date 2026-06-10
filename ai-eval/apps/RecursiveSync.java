// Recursive synchronized re-entry. Thread holds the same monitor through deep recursion.
// Should be reported as deep stack / heavy computation, NOT as deadlock or contention.
public class RecursiveSync {
    static final Object LOCK = new Object();

    static void recurse(int depth) {
        synchronized (LOCK) {
            if (depth <= 0) {
                // burn CPU at the bottom so this thread shows up
                long x = 0;
                for (long i = 0; i < 1_000_000L; i++) x ^= i * 31;
                if (x == 42) System.out.println(x);
                return;
            }
            recurse(depth - 1);
        }
    }

    public static void main(String[] args) throws Exception {
        Thread t = new Thread(() -> {
            while (true) recurse(50);
        }, "deep-recursor");
        t.setDaemon(true);
        t.start();
        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
