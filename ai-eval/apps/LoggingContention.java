// Logging framework contention: many threads hitting a synchronized logger.
// Simulates Log4j/Logback synchronous appender bottleneck.
public class LoggingContention {
    static final Object LOG_LOCK = new Object();
    static final StringBuilder LOG_BUFFER = new StringBuilder(1024 * 1024);

    static void log(String msg) {
        synchronized (LOG_LOCK) {
            LOG_BUFFER.append(msg).append('\n');
            if (LOG_BUFFER.length() > 512 * 1024) LOG_BUFFER.setLength(0);
        }
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 20; i++) {
            Thread t = new Thread(() -> {
                int n = 0;
                while (!Thread.currentThread().isInterrupted()) {
                    log("LOG " + Thread.currentThread().getName() + " event=" + n++);
                }
            }, "log-writer-" + i);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
