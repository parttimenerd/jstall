// Thousands of daemon threads created and left alive — thread leak pattern.
// daemon-leaked-N threads: all TIMED_WAITING in sleep but never cleaned up.
public class DaemonThreadStorm {
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 500; i++) {
            Thread t = new Thread(() -> {
                try { Thread.sleep(Long.MAX_VALUE); } catch (Exception e) {}
            }, "daemon-leaked-" + i);
            t.setDaemon(true);
            t.start();
            if (i % 50 == 0) Thread.sleep(10); // slight pacing
        }
        Thread.sleep(500);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
