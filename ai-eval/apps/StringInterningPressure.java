// Massive String.intern() calls causing Metaspace + GC pressure (intern table lives in Metaspace).
public class StringInterningPressure {
    public static void main(String[] args) throws Exception {
        Thread t = new Thread(() -> {
            long i = 0;
            while (true) {
                // intern unique strings → fills intern table → Metaspace growth + GC pressure
                String s = ("intern-key-" + (i++)).intern();
                if (i % 100_000 == 0) { s = null; System.gc(); }
            }
        }, "intern-spammer");
        t.setDaemon(true);
        t.start();
        Thread.sleep(1500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
