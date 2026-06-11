// Circuit breaker in half-open; threads spin-check permit in tight loop.
// cb-probe threads busy-wait checking if circuit is open, draining CPU.
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public class CircuitBreakerSpin {
    static AtomicBoolean circuitOpen = new AtomicBoolean(true);
    static volatile long permitResetTime = System.currentTimeMillis() + 60_000;
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 6; i++) {
            final int n = i;
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    // Half-open: spin-check if we're allowed to try
                    if (System.currentTimeMillis() > permitResetTime) {
                        circuitOpen.set(false);
                    }
                    if (!circuitOpen.get()) break; // would proceed with request
                    // No sleep — busy-wait polling the circuit state
                }
            }, "cb-probe-" + n);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(1000);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
