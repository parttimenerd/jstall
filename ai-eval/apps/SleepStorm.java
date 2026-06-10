// Many threads doing Thread.sleep — like a sleep-storm. All idle (TIMED_WAITING). Should be classified as healthy/idle, not contention.
public class SleepStorm {
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 20; i++) {
            final int id = i;
            Thread t = new Thread(() -> {
                while (true) {
                    try { Thread.sleep(10_000); } catch (InterruptedException e) { return; }
                }
            }, "sleeper-" + id);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(800);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "ready"); } catch (Exception e) {}
        Thread.sleep(120_000);
    }
}
