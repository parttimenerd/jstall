// Threads that should have stopped (interrupt was sent) but keep running because
// they catch InterruptedException without re-interrupting themselves.
// Visible as TIMED_WAITING threads that keep waking up (CPU spikes every 1s).
public class InterruptSwallowed {
    public static void main(String[] args) throws Exception {
        // 4 threads in a loop that should stop on interrupt but don't
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                int iterations = 0;
                while (true) { // never checks interrupted flag
                    try {
                        // Simulate work
                        long sum = 0;
                        for (int j = 0; j < 1_000_000; j++) sum += j;
                        Thread.sleep(500);
                        iterations++;
                    } catch (InterruptedException e) {
                        // BUG: swallow the interrupt — don't re-interrupt, don't break
                        // Thread.currentThread().interrupt() is missing
                        // Thread keeps running indefinitely
                    }
                }
            }, "interrupt-swallower-" + i);
            t.setDaemon(true);
            t.start();
        }

        // Send interrupt signals — should stop the threads but won't due to the bug
        Thread.sleep(400);
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("interrupt-swallower")) t.interrupt();
        }

        Thread.sleep(200);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
