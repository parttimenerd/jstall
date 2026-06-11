// Flood of short-lived hot methods trigger many concurrent JIT compilations.
// jit-caller threads call many distinct methods forcing JIT compiler threads active.
import java.util.concurrent.*;
public class JitWarmupStorm {
    static int hot1(int x) { return x * 2 + 1; }
    static int hot2(int x) { return x * 3 - 1; }
    static int hot3(int x) { return (x ^ (x >> 1)); }
    static int hot4(int x) { return Integer.bitCount(x) + x; }
    static int hot5(int x) { return x * x % 97; }
    public static void main(String[] args) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r); t.setDaemon(true);
            t.setName("jit-caller-" + t.getId()); return t;
        });
        for (int i = 0; i < 8; i++) {
            ex.submit(() -> {
                int x = 1;
                while (!Thread.currentThread().isInterrupted()) {
                    x = hot1(hot2(hot3(hot4(hot5(x)))));
                    if (x < 0) x = -x;
                }
            });
        }
        Thread.sleep(1500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
