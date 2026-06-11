// Resilience4j RateLimiter: 1 permit/10s, many callers blocked in the queue.
import io.github.resilience4j.ratelimiter.*;
import java.time.Duration;
public class Resilience4jRateLimiterQueue {
    public static void main(String[] args) throws Exception {
        var config = RateLimiterConfig.custom()
            .limitRefreshPeriod(Duration.ofSeconds(10))
            .limitForPeriod(1)
            .timeoutDuration(Duration.ofSeconds(120))
            .build();
        var limiter = RateLimiter.of("rl-gate", config);

        // First thread acquires the single permit immediately
        Thread first = new Thread(() -> {
            try {
                limiter.acquirePermission();
                Thread.sleep(Long.MAX_VALUE); // holds the slot
            } catch (InterruptedException e) {}
        }, "rl-permit-holder");
        first.setDaemon(true);
        first.start();
        Thread.sleep(100);

        // 8 callers pile up waiting for their turn
        for (int i = 0; i < 8; i++) {
            Thread t = new Thread(() -> {
                limiter.acquirePermission(); // blocks
            }, "rl-waiter-" + i);
            t.setDaemon(true);
            t.start();
        }

        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
