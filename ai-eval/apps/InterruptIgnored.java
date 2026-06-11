// Interrupted flag set on thread but code swallows interrupt and keeps blocking.
// interrupt-ignored-N threads swallow InterruptedException and re-block indefinitely.
import java.util.concurrent.*;
public class InterruptIgnored {
    static final SynchronousQueue<String> handoff = new SynchronousQueue<>();
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 5; i++) {
            final int n = i;
            Thread t = new Thread(() -> {
                while (true) {
                    try {
                        handoff.poll(60, TimeUnit.SECONDS); // blocks
                    } catch (InterruptedException e) {
                        // swallow interrupt and retry — never propagates
                        Thread.currentThread().interrupt(); // re-set but loop continues
                    }
                }
            }, "interrupt-ignored-" + n);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(300);
        // Interrupt all threads — they swallow it and keep blocking
        Thread.getAllStackTraces().keySet().stream()
            .filter(t -> t.getName().startsWith("interrupt-ignored-"))
            .forEach(Thread::interrupt);
        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
